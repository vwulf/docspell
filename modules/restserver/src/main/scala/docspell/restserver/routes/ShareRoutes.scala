/*
 * Copyright 2020 Eike K. & Contributors
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package docspell.restserver.routes

import cats.data.OptionT
import cats.effect._
import cats.implicits._

import docspell.backend.BackendApp
import docspell.backend.auth.AuthToken
import docspell.backend.ops.OShare
import docspell.common.Ident
import docspell.restapi.model._
import docspell.restserver.http4s.ResponseGenerator
import docspell.store.records.RShare

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

object ShareRoutes {

  def manage[F[_]: Async](backend: BackendApp[F], user: AuthToken): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] with ResponseGenerator[F] {}
    import dsl._

    HttpRoutes.of {
      case GET -> Root =>
        for {
          all <- backend.share.findAll(user.account.collective)
          res <- Ok(ShareList(all.map(mkShareDetail)))
        } yield res

      case req @ POST -> Root =>
        for {
          data <- req.as[ShareData]
          share = mkNewShare(data, user)
          res <- backend.share.addNew(share)
          resp <- Ok(mkIdResult(res, "New share created."))
        } yield resp

      case GET -> Root / Ident(id) =>
        (for {
          share <- backend.share.findOne(id, user.account.collective)
          resp <- OptionT.liftF(Ok(mkShareDetail(share)))
        } yield resp).getOrElseF(NotFound())

      case req @ PUT -> Root / Ident(id) =>
        for {
          data <- req.as[ShareData]
          share = mkNewShare(data, user)
          updated <- backend.share.update(id, share, data.removePassword.getOrElse(false))
          resp <- Ok(mkBasicResult(updated, "Share updated."))
        } yield resp

      case DELETE -> Root / Ident(id) =>
        for {
          del <- backend.share.delete(id, user.account.collective)
          resp <- Ok(BasicResult(del, if (del) "Share deleted." else "Deleting failed."))
        } yield resp
    }
  }

  def mkNewShare(data: ShareData, user: AuthToken): OShare.NewShare =
    OShare.NewShare(
      user.account.collective,
      data.name,
      data.query,
      data.enabled,
      data.password,
      data.publishUntil
    )

  def mkIdResult(r: OShare.ChangeResult, msg: => String): IdResult =
    r match {
      case OShare.ChangeResult.Success(id) => IdResult(true, msg, id)
      case OShare.ChangeResult.PublishUntilInPast =>
        IdResult(false, "Until date must not be in the past", Ident.unsafe(""))
    }

  def mkBasicResult(r: OShare.ChangeResult, msg: => String): BasicResult =
    r match {
      case OShare.ChangeResult.Success(_) => BasicResult(true, msg)
      case OShare.ChangeResult.PublishUntilInPast =>
        BasicResult(false, "Until date must not be in the past")
    }

  def mkShareDetail(r: RShare): ShareDetail =
    ShareDetail(
      r.id,
      r.query,
      r.name,
      r.enabled,
      r.publishAt,
      r.publishUntil,
      r.password.isDefined,
      r.views,
      r.lastAccess
    )
}
