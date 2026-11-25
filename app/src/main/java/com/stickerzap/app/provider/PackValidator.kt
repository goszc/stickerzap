package com.stickerzap.app.provider

import android.graphics.BitmapFactory
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.media.StickerProcessor
import java.io.File

object PackValidator {

    /**
     * Returns a user-facing error string, or null if the pack meets official WhatsApp rules.
     * Also generates a missing 96×96 tray icon from the first sticker when possible.
     */
    fun verifyAndPrepare(pack: StickerPack, packDir: File): String? {
        if (pack.identifier.isBlank()) return "O identificador do álbum está vazio."
        if (pack.identifier.length > StickerPackContract.MAX_CHAR_COUNT) {
            return "O identificador do álbum passa de ${StickerPackContract.MAX_CHAR_COUNT} caracteres."
        }
        if (!StickerPackContract.isValidIdentifier(pack.identifier)) {
            return "Identificador inválido (\"${pack.identifier}\"). Use apenas letras, números, _ - . , ' e espaço."
        }
        if (pack.name.isBlank()) return "O nome do álbum está vazio."
        if (pack.name.length > StickerPackContract.MAX_CHAR_COUNT) {
            return "O nome do álbum passa de ${StickerPackContract.MAX_CHAR_COUNT} caracteres."
        }
        if (pack.publisher.isBlank()) return "O autor do álbum está vazio."
        if (pack.publisher.length > StickerPackContract.MAX_CHAR_COUNT) {
            return "O autor do álbum passa de ${StickerPackContract.MAX_CHAR_COUNT} caracteres."
        }
        if (pack.stickers.size < StickerPackContract.MIN_STICKERS) {
            val missing = StickerPackContract.MIN_STICKERS - pack.stickers.size
            return "O WhatsApp exige no mínimo ${StickerPackContract.MIN_STICKERS} figurinhas neste álbum. " +
                "Faltam $missing. Adicione mais e tente de novo."
        }
        if (pack.stickers.size > StickerPackContract.MAX_STICKERS) {
            return "O WhatsApp aceita no máximo ${StickerPackContract.MAX_STICKERS} figurinhas por álbum."
        }

        val trayFile = File(packDir, pack.trayImageFile)
        if (!trayFile.exists()) {
            val first = pack.stickers.firstOrNull()?.let { File(packDir, it.imageFileName) }
            if (first == null || !first.exists()) {
                return "Falta o ícone da bandeja (tray.png) e não há figurinha para gerá-lo."
            }
            val bmp = BitmapFactory.decodeFile(first.absolutePath)
                ?: return "Não foi possível gerar o ícone da bandeja a partir da primeira figurinha."
            StickerProcessor.saveTrayIcon96(bmp, trayFile)
            if (!bmp.isRecycled) bmp.recycle()
        }
        val trayError = verifyTray(trayFile)
        if (trayError != null) return trayError

        pack.stickers.forEach { sticker ->
            val emojis = sticker.emojis.map { it.trim() }.filter { it.isNotEmpty() }
            if (emojis.size < StickerPackContract.MIN_EMOJIS) {
                return "A figurinha \"${sticker.name}\" precisa de pelo menos 1 emoji."
            }
            if (emojis.size > StickerPackContract.MAX_EMOJIS) {
                return "A figurinha \"${sticker.name}\" tem mais de ${StickerPackContract.MAX_EMOJIS} emojis."
            }
            val file = File(packDir, sticker.imageFileName)
            if (!file.exists()) {
                return "Arquivo da figurinha não encontrado: ${sticker.imageFileName}"
            }
            if (file.length() > StickerPackContract.STATIC_STICKER_MAX_BYTES) {
                val kb = file.length() / 1024
                return "A figurinha \"${sticker.name}\" tem ${kb}KB (limite: 100KB)."
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth != StickerPackContract.STICKER_SIZE_PX ||
                opts.outHeight != StickerPackContract.STICKER_SIZE_PX
            ) {
                return "A figurinha \"${sticker.name}\" precisa ter 512×512 px " +
                    "(atual: ${opts.outWidth}×${opts.outHeight})."
            }
        }
        return null
    }

    private fun verifyTray(trayFile: File): String? {
        if (!trayFile.exists()) return "Falta o ícone da bandeja (tray.png)."
        if (trayFile.length() > StickerPackContract.TRAY_MAX_BYTES) {
            return "O ícone da bandeja passa de 50KB."
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(trayFile.absolutePath, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w < StickerPackContract.TRAY_MIN_PX || w > StickerPackContract.TRAY_MAX_PX ||
            h < StickerPackContract.TRAY_MIN_PX || h > StickerPackContract.TRAY_MAX_PX
        ) {
            return "O ícone da bandeja deve ter entre 24 e 512 px (atual: ${w}×${h})."
        }
        return null
    }
}
