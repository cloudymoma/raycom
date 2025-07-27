### Media CDN

#### Pre-requisites

```shell
gcloud services enable networkservices.googleapis.com

gcloud services enable certificatemanager.googleapis.com
```

#### EdgeCacheOrigin resource

```shell
gcloud edge-cache origins create dingo-media-cdn \
    --origin-address="gs://dingo-cdn"
```

```shell
gcloud edge-cache origins list
```

#### EdgeCacheService resource

Update the `dingo-media-cdn-service.yaml` file according to your requirements.

```shell
gcloud edge-cache services import dingo-media-cdn-service \
    --source=dingo-media-cdn-service.yaml
```

#### Retrieve the IP addresses

```shell
gcloud edge-cache services describe dingo-media-cdn-service
```

#### Test the CDN

```shell
curl -svo /dev/null "http://DOMAIN_NAME/FILE_NAME"
```

If you did not configure DNS to point to your provisioned IP addresses, use the resolve option to override the address that curl uses

```shell
curl -svo /dev/null --resolve bindiego.com:80:<IP_Address> "http://bindiego.com/veo_videos/newton.mp4"
```
