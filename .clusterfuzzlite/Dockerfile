FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && apt-get install -y unzip

COPY . /src/octomil-android
WORKDIR /src/octomil-android

COPY .clusterfuzzlite/build.sh /src/build.sh
