/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aiotrade.lib.avro

import java.lang.reflect.ParameterizedType

import org.apache.avro.Schema
import org.apache.avro.Protocol
import org.apache.avro.AvroRuntimeException
import org.apache.avro.AvroTypeException
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData

object SpecificData {
  private val INSTANCE = new SpecificData

  private val NO_CLASS = (new Object {}).getClass
  private val NULL_SCHEMA = Schema.create(Schema.Type.NULL)

  val BooleanType = java.lang.Boolean.TYPE
  val ByteType = java.lang.Byte.TYPE
  val ShortType = java.lang.Short.TYPE
  val IntegerType = java.lang.Integer.TYPE
  val LongType = java.lang.Long.TYPE
  val FloatType = java.lang.Float.TYPE
  val DoubleType = java.lang.Double.TYPE
  val VoidType = java.lang.Void.TYPE

  val BooleanClass = classOf[scala.Boolean]
  val ByteClass = classOf[scala.Byte]
  val ShortClass = classOf[scala.Short]
  val IntClass = classOf[scala.Int]
  val LongClass = classOf[scala.Long]
  val FloatClass = classOf[scala.Float]
  val DoubleClass = classOf[scala.Double]
  val NullClass = classOf[scala.Null]
  val UnitClass = scala.Unit

  val JBooleanClass = classOf[java.lang.Boolean]
  val JByteClass = classOf[java.lang.Byte]
  val JShortClass = classOf[java.lang.Short]
  val JIntegerClass = classOf[java.lang.Integer]
  val JLongClass = classOf[java.lang.Long]
  val JFloatClass = classOf[java.lang.Float]
  val JDoubleClass = classOf[java.lang.Double]
  val JVoidClass = classOf[java.lang.Void]

  val SeqClass = classOf[collection.Seq[_]]
  val MapClass = classOf[collection.Map[_, _]]
  
  val JCollectionClass = classOf[java.util.Collection[_]]
  val JMapClass = classOf[java.util.Map[_, _]]

  val StringClass = classOf[String]
  val CharSequenceClass = classOf[java.lang.CharSequence]
  val ByteBufferClass = classOf[java.nio.ByteBuffer]
  val NumberClass = classOf[java.lang.Number]
  val BigDecimalClass = classOf[java.math.BigDecimal]
  val BigIntegerClass = classOf[java.math.BigInteger]
  val DateClass = classOf[java.util.Date]
  val SqlDateClass = classOf[java.sql.Date]
  val SqlTimeClass = classOf[java.sql.Time]
  val SqlTimestampClass = classOf[java.sql.Timestamp]

  /** Return the singleton instance. */
  def get(): SpecificData = INSTANCE
}

/** Utilities for generated Java classes and interfaces. */
import SpecificData._
class SpecificData protected () extends GenericData {
  import Schema.Type._

  private val classCache = new java.util.concurrent.ConcurrentHashMap[String, Class[_]]()
  private val schemaCache = new java.util.WeakHashMap[java.lang.reflect.Type, Schema]()

  override protected def isEnum(datum: Object): Boolean = {
    datum.isInstanceOf[Enum[_]] || super.isEnum(datum)
  }



  /** Return the class that implements a schema, or null if none exists. */
  def getClass(schema: Schema): Class[_] = {
    schema.getType match {
      case FIXED | RECORD | ENUM =>
        val name = schema.getFullName
        val c = classCache.get(name) match {
          case null =>
            try {
              Class.forName(getClassName(schema))
            } catch {
              case e: ClassNotFoundException => NO_CLASS
            }
          case x => x
        }
        classCache.put(name, c)
        
        if (c == NO_CLASS) null else c
      case ARRAY => classOf[java.util.List[_]]
      case MAP => classOf[java.util.Map[_, _]]
      case UNION =>
        val types = schema.getTypes     // elide unions with null
        if ((types.size == 2) && types.contains(NULL_SCHEMA))
          getClass(types.get(if (types.get(0).equals(NULL_SCHEMA)) 1 else 0))
        else
          classOf[Object]
      case STRING =>  classOf[java.lang.CharSequence]
      case BYTES =>   classOf[java.nio.ByteBuffer]
      case INT =>     java.lang.Integer.TYPE
      case LONG =>    java.lang.Long.TYPE
      case FLOAT =>   java.lang.Float.TYPE
      case DOUBLE =>  java.lang.Double.TYPE
      case BOOLEAN => java.lang.Boolean.TYPE
      case NULL =>    java.lang.Void.TYPE
      case _ => throw new AvroRuntimeException("Unknown type: " + schema)
    }
  }

  /** Returns the Java class name indicated by a schema's name and namespace. */
  def getClassName(schema: Schema): String = {
    val namespace = schema.getNamespace
    val name = schema.getName
    if (namespace == null) {
      name
    } else {
      val dot = if (namespace.endsWith("$")) "" else "."
      namespace + dot + name
    }
  }

  /** Find the schema for a Java type. */
  def getSchema(tpe: java.lang.reflect.Type): Schema = {
    schemaCache.get(tpe) match {
      case null =>
        val schema = createSchema(tpe, new java.util.LinkedHashMap[String, Schema]())
        schemaCache.put(tpe, schema)
        schema
      case x => x
    }
  }

  /** Create the schema for a Java type. */
  protected def createSchema(tpe: java.lang.reflect.Type, names: java.util.Map[String, Schema]): Schema = {
    tpe match {
      case c: Class[_] if CharSequenceClass.isAssignableFrom(c) =>
        Schema.create(Type.STRING)
      case ByteBufferClass =>
        Schema.create(Type.BYTES)
      case IntegerType| IntClass | JIntegerClass =>
        Schema.create(Type.INT)
      case LongType | LongClass | JLongClass =>
        Schema.create(Type.LONG)
      case JFloatClass | FloatType | FloatClass =>
        Schema.create(Type.FLOAT)
      case JDoubleClass | DoubleType | DoubleClass =>
        Schema.create(Type.DOUBLE)
      case JBooleanClass | BooleanType | BooleanClass =>
        Schema.create(Type.BOOLEAN)
      case JVoidClass | VoidType =>
        Schema.create(Type.NULL)
      case ptype: ParameterizedType =>
        val raw = ptype.getRawType.asInstanceOf[Class[_]]
        val params = ptype.getActualTypeArguments

        if (JCollectionClass.isAssignableFrom(raw) || SeqClass.isAssignableFrom(raw)) { 
          // array
          if (params.length != 1) throw new AvroTypeException("No array type specified.")
          Schema.createArray(createSchema(params(0), names))

        } else if (JMapClass.isAssignableFrom(raw) || MapClass.isAssignableFrom(raw)) {   
          // map
          val key = params(0)
          val value = params(1)
          tpe match {
            case c: Class[_] if CharSequenceClass.isAssignableFrom(c) =>
              Schema.createMap(createSchema(value, names))
            case _ => throw new AvroTypeException("Map key class not CharSequence: " + key)
          }
          
        } else {
          createSchema(raw, names)
        }
      case c: Class[_] =>
        val fullName = c.getName
        val schema = names.get(fullName) match {
          case null =>
            try {
              c.getDeclaredField("SCHEMA$").get(null).asInstanceOf[Schema]
            } catch {
              case e: NoSuchFieldException => throw new AvroRuntimeException(e)
              case e: IllegalAccessException => throw new AvroRuntimeException(e)
            }
          case schema => schema
        }
        names.put(fullName, schema)
        schema
      case _ => throw new AvroTypeException("Unknown type: " + tpe)
    }
  }

  protected def createSchema_old(tpe: java.lang.reflect.Type, names: java.util.Map[String, Schema]): Schema = {
    tpe match {
      case x: Class[_] if classOf[java.lang.CharSequence].isAssignableFrom(tpe.asInstanceOf[Class[_]]) =>
    }
    if (tpe.isInstanceOf[Class[_]] && classOf[java.lang.CharSequence].isAssignableFrom(tpe.asInstanceOf[Class[_]]))
      Schema.create(Type.STRING)
    else if (tpe == classOf[java.nio.ByteBuffer])
      Schema.create(Type.BYTES)
    else if (tpe == classOf[java.lang.Integer] || tpe == java.lang.Integer.TYPE)
      Schema.create(Type.INT)
    else if (tpe == classOf[java.lang.Long] || tpe == java.lang.Long.TYPE)
      Schema.create(Type.LONG)
    else if (tpe == classOf[Float] || tpe == java.lang.Float.TYPE)
      Schema.create(Type.FLOAT)
    else if (tpe == classOf[java.lang.Double] || tpe == java.lang.Double.TYPE)
      Schema.create(Type.DOUBLE)
    else if (tpe == classOf[java.lang.Boolean] || tpe == java.lang.Boolean.TYPE)
      Schema.create(Type.BOOLEAN)
    else if (tpe == classOf[java.lang.Void] || tpe == java.lang.Void.TYPE)
      Schema.create(Type.NULL)
    else if (tpe.isInstanceOf[ParameterizedType]) {
      val ptype = tpe.asInstanceOf[ParameterizedType]
      val raw = ptype.getRawType.asInstanceOf[Class[_]]
      val params = ptype.getActualTypeArguments
      if (classOf[java.util.Collection[_]].isAssignableFrom(raw)) { // array
        if (params.length != 1)
          throw new AvroTypeException("No array type specified.")
        Schema.createArray(createSchema(params(0), names))
      } else if (classOf[java.util.Map[_, _]].isAssignableFrom(raw)) {   // map
        val key = params(0)
        val value = params(1)
        if (!(tpe.isInstanceOf[Class[_]] && classOf[CharSequence].isAssignableFrom(tpe.asInstanceOf[Class[_]])))
          throw new AvroTypeException("Map key class not CharSequence: " + key)
        Schema.createMap(createSchema(value, names))
      } else {
        createSchema(raw, names)
      }
    } else if (tpe.isInstanceOf[Class[_]]) {               // class
      val c = tpe.asInstanceOf[Class[_]]
      val fullName = c.getName
      val schema = names.get(fullName) match {
        case null =>
          try {
            c.getDeclaredField("SCHEMA$").get(null).asInstanceOf[Schema]
          } catch {
            case e: NoSuchFieldException => throw new AvroRuntimeException(e)
            case e: IllegalAccessException => throw new AvroRuntimeException(e)
          }
        case schema => schema
      }
      names.put(fullName, schema)
      schema
    } else
      throw new AvroTypeException("Unknown type: " + tpe)
  }

  /** Return the protocol for a Java interface. */
  def getProtocol(iface: Class[_]): Protocol = {
    try {
      iface.getDeclaredField("PROTOCOL").get(null).asInstanceOf[Protocol]
    } catch {
      case e: NoSuchFieldException=> throw new AvroRuntimeException(e);
      case e: IllegalAccessException => throw new AvroRuntimeException(e);
    }
  }

  override def compare(o1: Object, o2: Object, s: Schema): Int = {
    s.getType match {
      case ENUM if !(o1.isInstanceOf[String]) =>               // not generic
        (o1.asInstanceOf[Enum[_]]).ordinal - (o2.asInstanceOf[Enum[_]]).ordinal
      case _ => super.compare(o1, o2, s)
    }
  }
}