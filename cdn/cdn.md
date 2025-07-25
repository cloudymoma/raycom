### CDN

#### setup GCS

```shell
gcloud storage buckets create gs://dingo-cdn \
  --project=du-hast-mich --default-storage-class=standard \
  --location=us-central1 --uniform-bucket-level-access
```

use `gcloud storage cp <local_file> gs://dingo-cdn/veo_videos/` to upload files to the bucket.

```shell
gcloud storage buckets add-iam-policy-binding \
  gs://dingo-cdn --member=allUsers --role=roles/storage.objectViewer
```

#### reserve ip

reserve an ip address

```shell
gcloud compute addresses create dingo-cdn-ip \
    --network-tier=PREMIUM \
    --ip-version=IPV4 \
    --global
```

examine the reserved ip address

```shell
gcloud compute addresses describe dingo-cdn-ip \
    --format="get(address)" \
    --global
```

#### config external LB

backend

```shell
gcloud compute backend-buckets create dingo-cdn-backend-bucket \
    --gcs-bucket-name=dingo-cdn \
    --enable-cdn \
    --cache-mode=USE_ORIGIN_HEADERS
```

url map

```shell
gcloud compute url-maps create dingo-http-cdn-lb \
    --default-backend-bucket=dingo-cdn-backend-bucket
```

target proxy

```shell
gcloud compute target-http-proxies create dingo-http-cdn-lb-proxy \
    --url-map=dingo-http-cdn-lb
```

forwarding rule

```shell
gcloud compute forwarding-rules create dingo-http-cdn-lb-forwarding-rule \
    --load-balancing-scheme=EXTERNAL_MANAGED \
    --network-tier=PREMIUM \
    --address=dingo-cdn-ip \
    --global \
    --target-http-proxy=dingo-http-cdn-lb-proxy \
    --ports=80
```

#### verify CDN

verify the CDN configuration

```shell
curl -I -o /dev/null /dev/null http://34.8.97.7/veo_videos/newton.mp4
```
