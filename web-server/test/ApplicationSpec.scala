/**
 * Copyright: Copyright (C) 2016, Jaguar Land Rover
 * License: MPL-2.0
 */

import play.api.test.Helpers._
import org.scalatestplus.play._
import play.api.libs.ws.WSClient

/**
 * Test the Application controller
 */
class ApplicationSpec extends PlaySpec with OneServerPerSuite {

  val wsClient = app.injector.instanceOf[WSClient]

  "send 404 on a bad request" in {
    val response = await(wsClient.url(s"http://localhost:$port/invalid").get())
    response.status mustBe NOT_FOUND
  }

  "render the index page" in {
    val response = await(wsClient.url(s"http://localhost:$port/").get())
    response.status mustBe OK
  }

}
