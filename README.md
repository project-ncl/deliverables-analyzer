# Deliverables Analyzer

Deliverables Analyzer is a RESTful Web Service that uses the
[Build Finder](https://github.com/project-ncl/build-finder) library to
scan a given URL containing a software distribution and return the list
of builds.

This application runs with:

- Quarkus
- Docker and Docker Compose

## Endpoints

The service supports the following endpoints:

- /health (/health/live and /health/ready)
- /api/analyze?url=\<url\>
- /api/version

## Building with Maven

To build with Maven and run the tests:

```
$ mvn clean install
```

## Creating Docker Images with Docker Compose

To also build the Docker image:

```
mvn -Pdocker clean install
```

This is the equivalent of manually running:

```
$ docker-compose pull
$ docker-compose up --build
$ docker-compose down --rmi --remove-orphans -v
```
