package org.aiotrade.lib.backtest

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.Logger
import org.aiotrade.lib.collection.ArrayList
import org.aiotrade.lib.math.indicator.SignalIndicator
import org.aiotrade.lib.math.signal.Side
import org.aiotrade.lib.math.signal.Signal
import org.aiotrade.lib.math.signal.SignalEvent
import org.aiotrade.lib.math.timeseries.TFreq
import org.aiotrade.lib.securities
import org.aiotrade.lib.securities.QuoteSer
import org.aiotrade.lib.securities.model.Exchange
import org.aiotrade.lib.securities.model.Execution
import org.aiotrade.lib.securities.model.Sec
import org.aiotrade.lib.trading.Account
import org.aiotrade.lib.trading.Broker
import org.aiotrade.lib.trading.Order
import org.aiotrade.lib.trading.OrderSide
import org.aiotrade.lib.trading.PaperBroker
import org.aiotrade.lib.trading.Position
import org.aiotrade.lib.trading.ShanghaiExpenseScheme
import org.aiotrade.lib.util.ValidTime
import org.aiotrade.lib.util.actors.Publisher
import scala.collection.mutable

case class Trigger(sec: Sec, position: Position, time: Long, side: Side)

/**
 * 
 * @author Caoyuan Deng
 */
class TradingService(broker: Broker, account: Account, tradeRule: TradeRule, referSer: QuoteSer, secPicking: SecPicking, signalIndTemplates: SignalIndicator*) extends Publisher {
  protected val log = Logger.getLogger(this.getClass.getName)
  
  protected val timestamps = referSer.timestamps.clone
  protected val freq = referSer.freq

  protected val signalIndicators = new mutable.HashSet[SignalIndicator]()
  protected val triggers = new mutable.HashSet[Trigger]()
  protected val pendingOrders = new ArrayList[OrderCompose]()
  protected var buyingOrders = List[Order]()
  protected var sellingOrders = List[Order]()

  protected var closedReferIdx = 0
  
  reactions += {
    case SecPickingEvent(secValidTime, side) =>
      val position = getPosition(secValidTime.ref)
      side match {
        case Side.ExitPicking if position == null =>
        case _ => triggers += Trigger(secValidTime.ref, position, secValidTime.validFrom, side)
      }
    
    case signalEvt@SignalEvent(ind, signal) if signalIndicators.contains(ind) && signal.isSign =>
      val sec = signalEvt.source.baseSer.serProvider.asInstanceOf[Sec]
      val time = signalEvt.signal.time
      val side = signalEvt.signal.kind.asInstanceOf[Side]
      val position = getPosition(sec)
      side match {
        case (Side.ExitLong | Side.ExitShort | Side.ExitPicking | Side.CutLoss | Side.TakeProfit) if (position == null) =>
        case _ => triggers += Trigger(sec, position, time, side)
      }
      
    case _ =>
  }

  private def initSignalIndicators {
    val t0 = System.currentTimeMillis
    if (signalIndTemplates.nonEmpty) {
      listenTo(Signal)
    
      for ((sec, _) <- secPicking.secToValidTimes;
           ser <- sec.serOf(freq);
           signalIndTemplate <- signalIndTemplates;
           indClass = signalIndTemplate.getClass;
           factor = signalIndTemplate.factors
      ) {
        val ind = indClass.newInstance.asInstanceOf[SignalIndicator]
        ind.factors = factor
        ind.set(ser)
        ind.computeFrom(0)
        signalIndicators += ind
      }
    }
    log.info("Inited singals in " + (System.currentTimeMillis - t0) / 1000 + "s.")
  }
  
  protected def closedTime = timestamps(closedReferIdx)
  
  protected def getPosition(sec: Sec): Position = account.positions.getOrElse(sec, null)
  
  protected def buy(sec: Sec): OrderCompose = {
    val order = new OrderCompose(sec, OrderSide.Buy, closedReferIdx)
    pendingOrders += order
    order
  }

  protected def sell(sec: Sec): OrderCompose = {
    val order = new OrderCompose(sec, OrderSide.Sell, closedReferIdx)
    pendingOrders += order
    order
  }
  
  def go(fromTime: Long, toTime: Long) {
    initSignalIndicators
    val fromIdx = timestamps.indexOfNearestOccurredTimeBehind(fromTime)
    val toIdx = timestamps.indexOfNearestOccurredTimeBefore(toTime)
    var i = fromIdx
    while (i <= toIdx) {
      closedReferIdx = i
      executeOrders
      updatePositionsPrice

      publish(ReportData(account.description, 0, closedTime, account.asset / account.initialAsset * 100))

      // todo process unfilled orders
      secPicking.go(closedTime)
      checkStopCondition
      at(i)
      processPendingOrders
      i += 1
    }
  }
  
  /**
   * At close of period idx, define the trading action for next period. 
   * @Note Override this method for your action.
   * @param idx: index of closed/passed period, this period was just closed/passed.
   */
  def at(idx: Int) {
    val triggers = scanTriggers(idx)
    for (Trigger(sec, position, triggerTime, side) <- triggers) {
      side match {
        case Side.EnterLong =>
          buy (sec) after (1)
        case Side.ExitLong =>
          sell (sec) after (1)
        case Side.EnterShort =>
        case Side.ExitShort =>
        case Side.CutLoss => 
          sell (sec) quantity (position.quantity) after (1)
        case Side.TakeProfit =>
          sell (sec) quantity (position.quantity) after (1)
        case _ =>
      }
    }
  }
  
  protected def checkStopCondition {
    for ((sec, position) <- account.positions) {
      if (tradeRule.cutLossRule(position)) {
        triggers += Trigger(sec, position, closedTime, Side.CutLoss)
      }
      if (tradeRule.takeProfitRule(position)) {
        triggers += Trigger(sec, position, closedTime, Side.TakeProfit)
      }
    }
  }

  private def updatePositionsPrice {
    for ((sec, position) <- account.positions; 
         ser <- sec.serOf(freq); 
         idx = ser.indexOfOccurredTime(closedTime) if idx >= 0
    ) {
      position.update(ser.close(idx))
    }
  }

  private def processPendingOrders {
    val orderSubmitReferIdx = closedReferIdx + 1 // next trading day
    if (orderSubmitReferIdx < timestamps.length) {
      val orderSubmitReferTime = timestamps(orderSubmitReferIdx)
      var toRemove = List[OrderCompose]()
      var buying = List[OrderCompose]()
      var selling = List[OrderCompose]()
      var i = 0
      while (i < pendingOrders.length) {
        val order = pendingOrders(i)
        if (order.referIndex < orderSubmitReferIdx) {
          toRemove ::= order
        } else if (order.referIndex == orderSubmitReferIdx) { 
          if (order.ser.exists(orderSubmitReferTime)) {
            order.side match {
              case OrderSide.Buy => buying ::= order
              case OrderSide.Sell => selling ::= order
              case _ =>
            }
          } else {
            order.side match {
              case OrderSide.Buy => // @todo pending after n days?
              case OrderSide.Sell => order after (1) // pending 1 day
              case _ =>
            }
          }
        }
        i += 1
      }
    
      val fundPerSec = account.balance / buying.length // @todo expenses
      buyingOrders = buying flatMap {_ fund (fundPerSec) toOrder}
      sellingOrders = selling flatMap {x => x toOrder}
    
      // @todo process unfilled orders from broker
      pendingOrders synchronized {
        pendingOrders --= toRemove
      }
    }
  }
  
  private def executeOrders {
    // sell first?. If so, how about the returning funds?
    buyingOrders map broker.prepareOrder foreach {orderExecutor => 
      orderExecutor.submit
      pseudoExecute(orderExecutor.order)
    }

    sellingOrders map broker.prepareOrder foreach {orderExecutor => 
      orderExecutor.submit
      pseudoExecute(orderExecutor.order)
    }
  }
  
  private def pseudoExecute(order: Order) {
    val execution = new Execution
    execution.sec = order.sec
    execution.time = closedTime
    execution.price = order.price
    execution.volume = order.quantity
    broker.processTrade(execution)
  }
  
  protected def scanTriggers(fromIdx: Int, toIdx: Int = -1): List[Trigger] = {
    val toIdx1 = if (toIdx == -1) fromIdx else toIdx
    scanTriggers(timestamps(math.max(fromIdx, 0)), timestamps(math.max(toIdx1, 0)))
  }
  
  protected def scanTriggers(fromTime: Long, toTime: Long): List[Trigger] = {
    var result = List[Trigger]()
    for (trigger <- triggers) {
      if (trigger.time >= fromTime && trigger.time <= toTime && secPicking.isValid(trigger.sec, toTime)) {
        result ::= trigger
      }
    }
    result
  }
  
  class OrderCompose(val sec: Sec, val side: OrderSide, referIdxAtDecision: Int) {
    val ser = sec.serOf(freq).get
    private var _price = Double.NaN
    private var _fund = Double.NaN
    private var _quantity = Double.NaN
    private var _afterIdx = 0

    def price(price: Double) = {
      _price = price
      this
    }

    def fund(fund: Double) = {
      _fund = fund
      this
    }
    
    def quantity(quantity: Double) = {
      _quantity = quantity
      this
    }
        
    def after(idx: Int) = {
      _afterIdx += idx
      this
    }
    
    def referIndex = referIdxAtDecision + _afterIdx

    def toOrder: Option[Order] = {
      val time = timestamps(referIndex)
      val idx = ser.indexOfOccurredTime(time)
      side match {
        case OrderSide.Buy =>
          if (_price.isNaN) {
            _price = tradeRule.buyPriceRule(ser.open(idx), ser.high(idx), ser.low(idx), ser.close(idx))
          }
          if (_quantity.isNaN) {
            _quantity = tradeRule.buyQuantityRule(ser.volume(idx), _price, _fund)
          }
        case OrderSide.Sell =>
          if (_price.isNaN) {
            _price = tradeRule.sellPriceRule(ser.open(idx), ser.high(idx), ser.low(idx), ser.close(idx))
          }
          if (_quantity.isNaN) {
            _quantity = tradeRule.sellQuantityRule(ser.volume(idx), _price, _fund)
          }
        case _ =>
      }

      if (_quantity > 0) {
        val order = new Order(account, sec, _quantity, _price, side)
        order.time = time
        Some(order)
      } else {
        None
      }
    }
  }

}

object TradingService {
  
  def createIndicator[T <: SignalIndicator](signalClass: Class[T], factors: Array[Double]): T = {
    val ind = signalClass.newInstance.asInstanceOf[T]
    ind.factorValues = factors
    ind
  }
  
  private def init = {
    val CSI300Category = "008011"
    val secs = securities.getSecsOfSector(CSI300Category)
    val referSec = Exchange.secOf("000001.SS").get
    val referSer = securities.loadSers(secs, referSec, TFreq.DAILY)
    val goodSecs = secs.filter{_.serOf(TFreq.DAILY).get.size > 0}
    println("Number of good secs: " + goodSecs.length)
    (goodSecs, referSer)
  }

  /**
   * Simple test
   */
  def main(args: Array[String]) {
    import org.aiotrade.lib.indicator.basic.signal._

    val df = new SimpleDateFormat("yyyy.MM.dd")
    val report = new Publisher {
      reactions += {
        case ReportData(name, id, time, value) => 
          println(df.format(new Date(time)) + ": " + value)
      }
    }
    val imageFileDir = System.getProperty("user.home") + File.separator + "backtest"
    val chartReport = new ChartReport(imageFileDir)
    
    val (secs, referSer) = init
    
    val secPicking = new SecPicking()
    secPicking ++= secs map (ValidTime(_, 0, 0))
    
    val broker = new PaperBroker("Backtest")
    val account = new Account("Backtest", 10000000.0, ShanghaiExpenseScheme(0.0008))
    val tradeRule = new TradeRule()
    val indTemplate = createIndicator(classOf[MACDSignal], Array(12, 26, 9))
    
    val tradingService = new TradingService(broker, account, tradeRule, referSer, secPicking, indTemplate) {
      override 
      def at(idx: Int) {
        val triggers = scanTriggers(idx)
        for (Trigger(sec, position, triggerTime, side) <- triggers) {
          side match {
            case Side.EnterLong =>
              buy (sec) after (1)
            case Side.ExitLong =>
              sell (sec) after (1)
            case Side.CutLoss => 
              sell (sec) quantity (position.quantity) after (1)
            case Side.TakeProfit =>
              sell (sec) quantity (position.quantity) after (1)
            case _ =>
          }
        }
      }
    }
    
    report.listenTo(tradingService)
    chartReport.listenTo(tradingService)

    val fromTime = df.parse("2011.04.03").getTime
    val toTime = df.parse("2012.04.03").getTime
    
    report.publish(RoundStarted(NoParam))
    tradingService.go(fromTime, toTime)
    report.publish(RoundFinished(NoParam))
  }
}
