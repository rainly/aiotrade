/*
 * PersistenceManager.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package org.aiotrade.platform.test

import org.aiotrade.lib.securities.PersistenceManager
import org.aiotrade.lib.securities.dataserver.QuoteServer
import org.aiotrade.lib.securities.dataserver.TickerServer
import org.aiotrade.lib.securities.Quote
import org.aiotrade.lib.math.timeseries.TFreq
import org.aiotrade.lib.math.timeseries.computable.Indicator
import org.aiotrade.lib.math.timeseries.descriptor.AnalysisContents
import org.aiotrade.lib.indicator.basic._

/**
 *
 * @author Caoyuan Deng
 */
class PlainPersistenceManager extends PersistenceManager {

  private val quoteServers  = List[QuoteServer]()  // new YahooQuoteServer
  private val tickerServers = List[TickerServer]() // new YahooTickerServer
  private val indicators    = List(new ARBRIndicator,
                                   new BIASIndicator,
                                   new BOLLIndicator,
                                   new CCIIndicator,
                                   new DMIIndicator,
                                   new EMAIndicator,
                                   new GMMAIndicator,
                                   new HVDIndicator,
                                   new KDIndicator,
                                   new MACDIndicator,
                                   new MAIndicator,
                                   new MFIIndicator,
                                   new MTMIndicator,
                                   new OBVIndicator,
                                   new ROCIndicator,
                                   new RSIIndicator,
                                   new SARIndicator,
                                   new WMSIndicator,
                                   new ZIGZAGFAIndicator,
                                   new ZIGZAGIndicator
  )

  def saveQuotes(symbol: String, freq: TFreq, quotes: Array[Quote], sourceId: Long) {}
  def restoreQuotes(symbol: String, freq: TFreq): Array[Quote] = Array[Quote]()
  def deleteQuotes(symbol: String, freq: TFreq, fromTime: Long, toTime: Long) {}
  def dropAllQuoteTables(symbol: String) {}

  def shutdown {}

  def restoreProperties {}
  def saveProperties {}

  def saveContents(contents: AnalysisContents) {}
  def restoreContents(symbol: String): AnalysisContents = new AnalysisContents(symbol)
  def defaultContents: AnalysisContents = new AnalysisContents("<Default>")

  def lookupAllRegisteredServices[T](clz: Class[T], folderName: String): Seq[T] = {
    if (clz == classOf[QuoteServer]) {
      quoteServers.asInstanceOf[Seq[T]]
    } else if (clz == classOf[TickerServer]) {
      tickerServers.asInstanceOf[Seq[T]]
    } else if (clz == classOf[Indicator]) {
      indicators.asInstanceOf[Seq[T]]
    } else {
      Nil
    }
  }

}