/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play

import kamon.Kamon
import kamon.context.{Context, HttpPropagation}
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}
import play.api.libs.ws.WSRequest

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

package object instrumentation {

  implicit class ChannelSyntax(val channel: org.jboss.netty.channel.Channel) extends AnyVal {
    def toContextAware(): ChannelContextAware =
      channel.asInstanceOf[ChannelContextAware]

    def getContext(): Context =
      channel.toContextAware().getContext
  }

  implicit class HttpSyntax(val obj: AnyRef) extends AnyVal {
    def toHttpRequest(): HttpRequest =
      obj.asInstanceOf[HttpRequest]

    def isHttpRequest(): Boolean =
      obj.isInstanceOf[HttpRequest]

    def toHttpResponse(): HttpResponse =
      obj.asInstanceOf[HttpResponse]

    def isHttpResponse(): Boolean =
      obj.isInstanceOf[HttpResponse]
  }

  def encodeContext(ctx: Context, request: WSRequest): WSRequest = {
    var newHeaders: Seq[(String, String)] = Seq.empty
    val headerWriter = new HttpPropagation.HeaderWriter {
      override def write(header: String, value: String): Unit =
        newHeaders = (header -> value) +: newHeaders
    }

    Kamon.defaultHttpPropagation().write(ctx, headerWriter)
    request.withHeaders(newHeaders: _*)
  }

  def decodeContext(request: HttpRequest): Context = {
    val headerReader = new HttpPropagation.HeaderReader {
      override def read(header: String): Option[String] =
        Option(request.headers().get(header))

      override def readAll(): Map[String, String] =
        request.headers().entries().asScala.map(e => (e.getKey, e.getValue)).toMap
    }

    Kamon.defaultHttpPropagation().read(headerReader)
  }

  def isError(statusCode: Int): Boolean =
    statusCode >= 500 && statusCode < 600

  object StatusCodes {
    val NotFound = 404
  }
}
