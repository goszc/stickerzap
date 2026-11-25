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

## Changelog

### 4.0 — vídeo para figurinha animada

- Importa um vídeo, escolhe um trecho (padrão até 3 s, máximo 10 s na figurinha e 5 s no caminho com som) e recorta em quadrado.
- Gera WebP animado 512×512 de até 500 KB e guarda o áudio do trecho para o preview dentro do app. A figurinha fica muda até tocar no alto-falante, que toca o clipe 3 vezes, como no WhatsApp.
- **Compartilhar áudio** envia o clipe `.m4a` para o WhatsApp (ou para outro app, se o WhatsApp não estiver instalado).
- **Fazer com som no WhatsApp** salva o vídeo quadrado em `Movies/StickerZap` e o áudio em `Music/StickerZap`, mostra o passo a passo do editor do próprio WhatsApp e abre o app.

O som não entra pela API oficial de pacotes. No WhatsApp, a figurinha do álbum chega animada e sem som. Uma figurinha com som é um WebP mais um `AudioMessage` Opus separado, e só o editor do WhatsApp anexa esse áudio. Em 24/11/2026 a equipe do WhatsApp respondeu que a API para terceiros ainda não existe, mas está no roadmap: [WhatsApp/stickers#1163](https://github.com/WhatsApp/stickers/issues/1163). Este app não usa hacks de protocolo (Baileys, WhatsApp Web ou similares). A análise do formato na rede está em [docs/research-sound-stickers.md](docs/research-sound-stickers.md) e em [WhiskeySockets/Baileys#2932](https://github.com/WhiskeySockets/Baileys/issues/2932).
