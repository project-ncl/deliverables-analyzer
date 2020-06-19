# Deliverables Analyzer

Deliverables Analyzer is a RESTful Web Service that uses the
[Build Finder](https://github.com/project-ncl/build-finder) library to
scan a given URL containing a software distribution and return the list
of builds.

## Endpoints

### Analyze

The main way to use the service is as follows:

- Perform an HTTP POST passing the `url` of a *deliverable* to
  `/api/analyze`. A *deliverable* should be an archive, such as a `.zip`
  or `.jar` file, which contains a product version. For example, if your
  product is `jbossfoo` and your version is `1.0`, then you might have a
  file called `jbossfoo-1.0.zip` to analyze. The `url` must be using
  protocol `http` or `https`. You may also optionally set `config` to
  override some of the default configuration settings. The `config` is
  the JSON representation of
  `org.jboss.pnc.build.finder.core.BuildConfig`.
- The `/api/analyze` endpoint will return the status code `201 Created`
  with a `Location` header. The location will be set to
  `/api/analyze/results/<id>` where `<id>` is an identifier
  corresponding to the `url`. The results will be cached, but will
  eventually expire. You may fetch the configuration used by accessing
  the `/api/analyze/configs/<id>` endpoint.
- The `/api/analyze/results/<id>` endpoint will return status code `404
  Not Found` if `<id>` doesn't exist. It will return `503 Service
  Unavailable` if the results exist, but are not yet ready. It will
  return `200 OK` if the results exist and are ready. In case there is
  an error getting the results, it will return `500 Server Error`.
- The `/api/analyze/statuses/<id>` endpoint will return the current
  status (percent done) of the analysis and may be polled once the
  analysis has started.

### Health

The service supports the Micoprofile `/health` endpoint (and also
`/health/live` and `/health/ready`).

### Version

The service will reply to `/api/version` with a version string in
plaintext containing information about the service as well as the
version of [Build Finder](https://github.com/project-ncl/build-finder)
being used.

## Building with Maven

To build with Maven and run the tests:

```
$ mvn -Ddistribution.url=<url> clean install
```

## Creating Docker Images with Docker Compose

To also build the Docker image, add `-Pdocker` to the `mvn` arguments.

This is the equivalent of manually running:

```
$ docker-compose pull
$ docker-compose up --build
$ docker-compose down --rmi --remove-orphans -v
```
