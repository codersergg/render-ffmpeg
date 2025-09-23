FROM eclipse-temurin:21-jdk

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ffmpeg \
      fontconfig \
      fonts-dejavu-core \
      fonts-liberation \
      fonts-freefont-ttf \
      fonts-noto-core \
      fonts-noto-cjk \
      fonts-noto-color-emoji \
      fonts-inter \
      fonts-roboto \
      fonts-montserrat \
      imagemagick \
      webp \
      gifsicle \
      jpegoptim optipng pngquant \
      sox libsox-fmt-all \
      lame vorbis-tools opus-tools flac \
      mediainfo mkvtoolnix \
      curl ca-certificates unzip jq locales && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN sed -i 's/# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    sed -i 's/# *ru_RU.UTF-8 UTF-8/ru_RU.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen

ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

RUN fc-cache -f
ENV FONTS_DIR="/usr/share/fonts:/usr/local/share/fonts"

HEALTHCHECK --interval=1m --timeout=5s CMD ffmpeg -v error -hide_banner -version >/dev/null 2>&1 || exit 1
