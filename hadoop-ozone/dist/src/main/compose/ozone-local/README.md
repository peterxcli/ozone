<!---
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

# Ozone Local Compose Example

This Compose definition runs the packaged `ozone local run` command in a single
container. The quickstart surface is intentionally small: the Compose file only
sets the local S3 access key and secret key, then lets `ozone local run` use its
defaults for the single-node cluster.

## Usage

Start the container:

```bash
docker-compose up -d
docker-compose logs -f local
```

The startup summary prints the suggested local AWS settings. The default S3
endpoint from this example is `http://127.0.0.1:9878`. Recon is also started
by `ozone local run` and is available at `http://127.0.0.1:9888`.

Example AWS CLI invocation from the host:

```bash
AWS_ACCESS_KEY_ID=admin \
AWS_SECRET_ACCESS_KEY=admin123 \
AWS_REGION=us-east-1 \
aws --endpoint-url http://127.0.0.1:9878 s3 ls
```

Start the optional MinIO-compatible console sidecar:

```bash
docker-compose --profile console up -d
```

The console is available at `http://127.0.0.1:9090`. This console is a
third-party MinIO admin UI. It starts against the local Ozone S3 endpoint, but
its login flow expects MinIO-specific STS/admin behavior. The local Ozone S3
credentials, `admin` / `admin123`, are valid for AWS CLI and SDK access, but
they do not currently log into this console sidecar.

Stop and remove the container and its named volume:

```bash
docker-compose down -v
```

## Advanced configuration

The minimal Compose file pins the S3 Gateway and Recon ports with
`ozone local run --s3g-port 9878 --with-recon --recon-port 9888` so the host can
publish stable local endpoints. Additional local runtime settings can still be
provided with `OZONE_LOCAL_*` environment variables, for example:

```yaml
environment:
  AWS_ACCESS_KEY_ID: admin
  AWS_SECRET_ACCESS_KEY: admin123
  OZONE_LOCAL_DATANODES: 2
  OZONE_LOCAL_FORMAT: always
  OZONE_LOCAL_RECON_ENABLED: true
  OZONE_LOCAL_STARTUP_TIMEOUT: 180s
```
