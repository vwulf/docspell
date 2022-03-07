/*
 * Copyright 2020 Eike K. & Contributors
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package docspell.joex.filecopy

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._

import docspell.common.FileCopyTaskArgs.Selection
import docspell.common.{FileCopyTaskArgs, Ident}
import docspell.joex.Config
import docspell.joex.scheduler.Task
import docspell.logging.Logger
import docspell.store.file.{BinnyUtils, FileRepository, FileRepositoryConfig}

import binny.CopyTool.Counter
import binny.{BinaryId, BinaryStore, CopyTool}
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}

object FileCopyTask {
  type Args = FileCopyTaskArgs

  case class CopyResult(success: Boolean, message: String, counter: List[Counter])
  object CopyResult {
    def noSourceImpl: CopyResult =
      CopyResult(false, "No source BinaryStore implementation found!", Nil)

    def noTargetImpl: CopyResult =
      CopyResult(false, "No target BinaryStore implementation found!", Nil)

    def noSourceStore(id: Ident): CopyResult =
      CopyResult(
        false,
        s"No source file repo found with id: ${id.id}. Make sure it is present in the config.",
        Nil
      )

    def noTargetStore: CopyResult =
      CopyResult(false, "No target file repositories defined", Nil)

    def success(counter: NonEmptyList[Counter]): CopyResult =
      CopyResult(true, "Done", counter.toList)

    implicit val binaryIdCodec: Codec[BinaryId] =
      Codec.from(
        Decoder.decodeString.map(BinaryId.apply),
        Encoder.encodeString.contramap(_.id)
      )

    implicit val counterEncoder: Codec[Counter] =
      deriveCodec
    implicit val jsonCodec: Codec[CopyResult] =
      deriveCodec
  }

  def onCancel[F[_]]: Task[F, Args, Unit] =
    Task.log(_.warn(s"Cancelling ${FileCopyTaskArgs.taskName.id} task"))

  def apply[F[_]: Async](cfg: Config): Task[F, Args, CopyResult] =
    Task { ctx =>
      val src = ctx.args.from
        .map(id =>
          cfg.files.getFileRepositoryConfig(id).toRight(CopyResult.noSourceStore(id))
        )
        .getOrElse(Right(cfg.files.defaultFileRepositoryConfig))

      val targets = ctx.args.to match {
        case Selection.All =>
          cfg.files.enabledStores.values.toList
            .map(FileRepositoryConfig.fromFileStoreConfig(cfg.files.chunkSize, _))
        case Selection.Stores(ids) =>
          ids.traverse(cfg.files.getFileRepositoryConfig).map(_.toList).getOrElse(Nil)
      }

      // remove source from targets if present there
      val data =
        for {
          srcConfig <- src
          trgConfig <- NonEmptyList
            .fromList(targets.filter(_ != srcConfig))
            .toRight(CopyResult.noTargetStore)

          srcRepo = ctx.store.createFileRepository(srcConfig, true)
          targetRepos = trgConfig.map(ctx.store.createFileRepository(_, false))
        } yield (srcRepo, targetRepos)

      data match {
        case Right((from, tos)) =>
          ctx.logger.info(s"Start copying all files from ") *>
            copy(ctx.logger, from, tos).flatTap(r =>
              if (r.success) ctx.logger.info(s"Copying finished: ${r.counter}")
              else ctx.logger.error(s"Copying failed: $r")
            )

        case Left(res) =>
          ctx.logger.error(s"Copying failed: $res") *> res.pure[F]
      }
    }

  def copy[F[_]: Async](
      logger: Logger[F],
      from: FileRepository[F],
      to: NonEmptyList[FileRepository[F]]
  ): F[CopyResult] =
    FileRepository.getDelegate(from) match {
      case None =>
        CopyResult.noSourceImpl.pure[F]

      case Some((src, srcMeta)) =>
        to.traverse(FileRepository.getDelegate).map(_.map(_._1)) match {
          case None =>
            CopyResult.noTargetImpl.pure[F]

          case Some(targets) =>
            val log = BinnyUtils.LoggerAdapter(logger)
            val maxConcurrent = {
              val nCores = Runtime.getRuntime.availableProcessors()
              if (nCores > 2) nCores / 2 else 1
            }

            def copyTo(to: BinaryStore[F]) =
              CopyTool.copyAll[F](log, src, srcMeta, to, 50, maxConcurrent)

            logger.info(s"Start copying ${from.config} -> ${to.map(_.config)}") *>
              targets.traverse(copyTo).map(CopyResult.success)
        }
    }
}
