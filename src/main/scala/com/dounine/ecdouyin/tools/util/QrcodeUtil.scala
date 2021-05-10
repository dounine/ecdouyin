package com.dounine.ecdouyin.tools.util

import com.google.zxing.client.j2se.{
  BufferedImageLuminanceSource,
  MatrixToImageWriter
}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.{
  BarcodeFormat,
  BinaryBitmap,
  MultiFormatReader,
  MultiFormatWriter
}

import java.io.{File, FileInputStream}
import java.nio.file.Paths
import javax.imageio.ImageIO

object QrcodeUtil {

  def create(
      data: String,
      path: String,
      charset: String,
      width: Int,
      height: Int
  ): File = {
    val matrix = new MultiFormatWriter().encode(
      new String(data.getBytes(charset), charset),
      BarcodeFormat.QR_CODE,
      width,
      height
    )
    val file = Paths.get(path)

    MatrixToImageWriter.writeToPath(
      matrix,
      path.split(".").last,
      file
    )
    file.toFile
  }

  def parse(file: File, charset: String): Option[String] = {
    try {
      val binaryBitmap = new BinaryBitmap(
        new HybridBinarizer(
          new BufferedImageLuminanceSource(
            ImageIO.read(new FileInputStream(file))
          )
        )
      )
      val result = new MultiFormatReader().decode(binaryBitmap)
      Option(result.getText())
    } catch {
      case e => Option.empty
    }
  }

}
