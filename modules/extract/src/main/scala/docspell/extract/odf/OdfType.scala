package docspell.extract.odf

import docspell.common.MimeType

object OdfType {

  val odt = MimeType.application("application/vnd.oasis.opendocument.text")
  val ods = MimeType.application("application/vnd.oasis.opendocument.spreadsheet")
  val odtAlias = MimeType.application("application/x-vnd.oasis.opendocument.text")
  val odsAlias = MimeType.application("application/x-vnd.oasis.opendocument.spreadsheet")

  val container = MimeType.zip

  val all = Set(odt, ods, odtAlias, odsAlias)

  def unapply(mt: MimeType): Option[MimeType] =
    Some(mt).filter(all.contains)
}
