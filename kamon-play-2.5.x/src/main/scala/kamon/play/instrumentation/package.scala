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

import io.netty.handler.codec.http.HttpRequest
import kamon.Kamon
import kamon.context.{Context, HttpPropagation}
import play.api.libs.ws.WSRequest
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

package object instrumentation {

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
