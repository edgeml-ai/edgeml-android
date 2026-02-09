FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && apt-get install -y unzip

COPY . /src/edgeml-android
WORKDIR /src/edgeml-android

COPY .clusterfuzzlite/build.sh /src/build.sh
