/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data

import io.circe.{Decoder, Encoder, Json}
import org.genivi.sota.core.data.UpdateStatus.UpdateStatus
import slick.driver.MySQLDriver.api._
import org.genivi.sota.core.data.VehicleStatus.VehicleStatus
import org.genivi.sota.core.db.{UpdateSpecs, Vehicles}
import org.genivi.sota.core.db.Vehicles.VehicleTable
import org.genivi.sota.data.Namespace._
import org.genivi.sota.data.Vehicle
import java.time.Instant
import org.genivi.sota.refined.SlickRefined._
import io.circe.syntax._
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._

import scala.concurrent.ExecutionContext

object VehicleStatus extends Enumeration {
  type VehicleStatus = Value

  val NotSeen, Error, UpToDate, Outdated = Value

  implicit val encoder : Encoder[VehicleStatus] = Encoder[String].contramap(_.toString)
  implicit val decoder : Decoder[VehicleStatus] = Decoder[String].map(VehicleStatus.withName)
}

case class VehicleUpdateStatus(vin: Vehicle.Vin, status: VehicleStatus, lastSeen: Option[Instant])

object VehicleSearch {
  import UpdateSpecs._
  import VehicleStatus._

  import org.genivi.sota.db.SlickExtensions.jodaDateTimeMapping

  def search(ns: Namespace, regex: Option[String], includeStatus: Boolean)
            (implicit db: Database, ec: ExecutionContext): DBIO[Json] = {
    val findQuery = regex match {
      case Some(r) => Vehicles.searchByRegex(ns, r)
      case _ => Vehicles.all(ns)
    }

    if(includeStatus) {
      VehicleSearch.withStatus(findQuery) map (_.asJson)
    } else {
      val maxVehicleCount = 1000
      VehicleSearch.withoutStatus(findQuery.take(maxVehicleCount)) map (_.asJson)
    }
  }

  def currentVehicleStatus(lastSeen: Option[Instant], updateStatuses: Seq[(Instant, UpdateStatus)]): VehicleStatus = {
    import UpdateStatus._

    if(lastSeen.isEmpty) {
      NotSeen
    } else {
      val statuses = updateStatuses.sortBy(_._1).reverse.map(_._2)

      if(statuses.headOption.contains(UpdateStatus.Failed)) {
        Error
      } else if(!statuses.exists(s => List(Canceled, Finished, Failed).contains(s))) {
        Outdated
      } else {
        UpToDate
      }
    }
  }

  private def withoutStatus(findQuery: Query[VehicleTable, Vehicle, Seq]): DBIO[Seq[Vehicle]] = {
    findQuery.result
  }

  private def withStatus(vehicleQuery: Query[VehicleTable, Vehicle, Seq])
                        (implicit db: Database, ec: ExecutionContext): DBIO[Seq[VehicleUpdateStatus]] = {
    val updateSpecsByVin = updateSpecs.map(us => (us.vin, (us.creationTime, us.status)))

    val updateStatusByVin = vehicleQuery
      .joinLeft(updateSpecsByVin).on(_.vin === _._1)
      .map { case (vehicle, statuses) => (vehicle, statuses.map(_._2)) }
      .result

    updateStatusByVin.map {
      _.groupBy { case (vehicle, _) => vehicle.vin }
        .values
        .map { v => (v.head._1, v.flatMap(_._2)) }
        .map { case (vehicle, statuses) =>
          VehicleUpdateStatus(vehicle.vin,
            currentVehicleStatus(vehicle.lastSeen, statuses),
            vehicle.lastSeen)
        }.toSeq
    }
  }
}
