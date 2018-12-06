package io.flashbook.flashbot.util

import java.io.File

package object files {
  def rmRf(file: File) {
    if (!file.exists) return
    if (file.isFile) {
      file.delete()
    } else {
      file.listFiles().foreach(rmRf)
      file.delete()
    }
  }
}
