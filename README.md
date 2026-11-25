# StickerZap

App Android em Kotlin + Jetpack Compose para criar figurinhas de WhatsApp (512×512 WebP), organizar álbuns e importar o pacote no WhatsApp pela API oficial.

## Como gerar o APK

1. Abra esta pasta no **Android Studio** (Ladybug ou mais recente) e espere o Gradle sincronizar.
2. Conecte um celular (depuração USB) ou use um emulador e clique em **Run**, ou vá em **Build > Build APK(s)**.
3. Pela linha de comando: `./gradlew assembleDebug` (JDK 17 e Android SDK 35).

O wrapper usa **Gradle 8.9**. Se `gradle/wrapper/gradle-wrapper.jar` não estiver no repositório, gere de novo com `gradle wrapper`.

## Importar no WhatsApp

- Cada álbum precisa de **no mínimo 3 figurinhas**.
- O app envia o pacote pela API oficial (`ContentProvider` + intent `ENABLE_STICKER_PACK`).
- Se o WhatsApp recusar o pacote, o app mostra o erro de validação.

## Figurinhas com som

O estúdio grava e reproduz áudio no preview interno. A API oficial de terceiros do WhatsApp **ainda não aceita figurinhas com som**, então o áudio não entra no pacote importado. Detalhes em [docs/research-sound-stickers.md](docs/research-sound-stickers.md).
