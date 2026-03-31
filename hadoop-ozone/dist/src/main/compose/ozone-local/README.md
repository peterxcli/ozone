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
container. The cluster is configured only through `OZONE_LOCAL_*` environment
variables in [`docker-compose.yaml`](./docker-compose.yaml); it does not rely on
an extra `ozone-site.xml` overlay.

## Usage

Start the container:

```bash
docker-compose up -d
docker-compose logs -f local
```

The startup summary prints the suggested local AWS settings. The default S3
endpoint from this example is `http://127.0.0.1:9878`.

Example AWS CLI invocation from the host:

```bash
AWS_ACCESS_KEY_ID=admin \
AWS_SECRET_ACCESS_KEY=admin123 \
AWS_REGION=us-east-1 \
aws --endpoint-url http://127.0.0.1:9878 s3 ls
```

Stop and remove the container and its named volume:

```bash
docker-compose down -v
```
