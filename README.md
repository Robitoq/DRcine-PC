# DRcine PC

Versão de **PC (Windows)** do DRcine, em um projeto separado do app de Android.
Aqui nada mexe no repositório do celular.

Feito com Kotlin + Compose Desktop. A pasta `shared/` é a mesma lógica do app do celular
(addons, catálogo, servidores), sem nada de Android. O vídeo toca pelo **VLC**.

## O que já tem (versão 1)
- Início, Filmes e Séries com prateleiras de catálogo
- Busca
- Detalhes no estilo streaming: fundo grande, selos, pílulas de ano/gênero, elenco com fotos e episódios por temporada
- Abre o filme em tela cheia; botão "Tela" (tecla A) troca a proporção: Original, Preencher, Esticar, 16:9, 4:3, 21:9
- Player (VLC) com controles por cima do filme (somem sozinhos): pausar, ±10 s, barra de tempo, volume, tela cheia, troca de servidor
- Atalhos: Espaço pausa, ← → pulam 10 s, ↑ ↓ volume, F tela cheia, Esc sai
- Séries: o próximo episódio abre sozinho no fim (cartão "A seguir" nos últimos 20 s), botão "Próximo ep." (tecla N) e lista de episódios ao lado dos servidores
- Continuar de onde parou, histórico e "Minha lista"
- Instalar e remover addons colando o link do manifest

## O que ainda não tem
Login/conta, amigos, chat, "Assistir juntos", legendas e notificações.
Dependem de partes que só existem no Android e entram numa próxima etapa.

## Como usar
1. Pegue o DRcine na aba **Releases** deste repositório.
2. Baixe `DRcine-Setup.exe`, dê dois cliques e pronto. Para desinstalar: Menu Iniciar > Desinstalar DRcine
   (ou Configurações > Aplicativos). Se preferir sem instalar, use `DRcine-PC-portatil.zip` e abra `DRcine.exe`.

O player de vídeo (VLC portátil) já vem dentro do programa: não precisa instalar o VLC.

Os dados (addons, histórico, lista) ficam em `C:\Users\SEU_USUARIO\.drcine-pc\dados.json`.

## Como gerar pelo GitHub
Cada envio para a branch `main` roda o workflow **Gerar DRcine para Windows**
(`.github/workflows/windows.yml`) e publica o resultado em Releases.
Também dá para rodar na mão: aba **Actions > Gerar DRcine para Windows > Run workflow**.

## Rodar no seu computador (opcional)
Precisa de JDK 17 e Gradle 8.7 (ou abra a pasta no IntelliJ IDEA):

    gradle :desktop:run

## Mantendo a lógica em dia com o app do celular
`shared/` aqui é uma cópia da pasta `shared/` do DRcine do Android.
Se você mudar a lógica no app do celular, copie a pasta de novo para cá.
Só o `shared/build.gradle.kts` é diferente (no PC o `org.json` precisa vir junto).
