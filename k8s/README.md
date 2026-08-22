# Kubernetes manifests

```bash
kubectl create secret generic cde-platform-secrets \
  --from-literal=jwt-secret="$(openssl rand -base64 48)" \
  --from-literal=db-username="cde" \
  --from-literal=db-password="$(openssl rand -base64 32)" \
  --from-literal=anthropic-api-key=""

kubectl apply -k k8s/
```

Edit the image registry in `kustomization.yaml` and the host in `ingress.yaml`
first; both carry placeholders.

---

## Read this before deploying anything real

**Data survives a restart now.** PostgreSQL runs as a StatefulSet with its own
volume, and Flyway owns the schema. That was not true before: the database used
to be H2 held in the pod's memory, and every restart destroyed everything.

What is still missing is everything around the database rather than the
database itself: **no backups, no point-in-time recovery, no failover, no
tested restore.** A single PostgreSQL pod on a single volume is one lost
PersistentVolume away from the same outcome as before, just less often. For
anything that matters, delete `postgres.yaml`, drop it from
`kustomization.yaml`, and point `SPRING_DATASOURCE_URL` at a managed database
— backups and recovery are the hard parts and none of them are here.

## Why one replica

`replicas: 1` is not a placeholder to raise when traffic grows. One reason
remains, where there were two. PostgreSQL settled the database
objection — every replica would now talk to the same one. What is left is the
**uploads volume**: it is `ReadWriteOnce`, so a second pod cannot mount it and
sits `Pending` indefinitely, which presents as a deployment that never
finishes rather than as an error.

Moving uploads to object storage is what unblocks scaling. `hpa.yaml` exists
and is deliberately left out of `kustomization.yaml` until then; its header
says so.

## Why the converter is a sidecar

The application sends the converter an **absolute filesystem path** and the
converter opens it directly. They share a volume, not an API. That is what a
pod is for, and it keeps the storage requirement at `ReadWriteOnce`, which any
default StorageClass provides.

The cost is that they scale together, and the converter — the CPU-bound half —
cannot be scaled on its own. Splitting them needs `ReadWriteMany` storage, or
better, uploads moved to object storage so pods share nothing. That is the
same change that unblocks the HPA.

## Probes

Liveness and readiness are different questions and are answered separately.

- `/actuator/health/liveness` — should this pod be restarted? Depends on
  nothing outside the process.
- `/actuator/health/readiness` — should it receive traffic? Includes the
  database.

Neither includes the converter. It contributes `DEGRADED` to the aggregate at
`/actuator/health`, which is worth alerting on, but a converter outage leaves
PDFs, markup and every existing document working — so it must not take pods
out of rotation.

A `startupProbe` covers the slow first start rather than a long
`initialDelaySeconds`, so liveness stays responsive once the pod is up.

## Shutdown

`terminationGracePeriodSeconds: 45` against the application's 25s graceful
shutdown, plus a 5s `preStop` pause. Endpoint removal and `SIGTERM` are sent
at the same moment and race; the pause lets the endpoint removal propagate so
in-flight uploads are not cut off by a shutdown that began before traffic
stopped arriving.

## Security notes

- **The Secret is required.** `CDE_JWT_SECRET` is read with `optional: false`,
  so a missing Secret stops the pod. This is deliberate: `application.yml`
  carries a signing key as a development placeholder, and a pod that silently
  fell back to it would accept tokens minted by anyone with the repository.
- **`secret.example.yaml` is a template.** It is not in `kustomization.yaml`.
  Create the real Secret out of band and never commit a filled-in copy.
- **`networkpolicy.yaml` matters more than it looks.** The converter takes a
  filesystem path over unauthenticated HTTP. Inside the pod only the
  application can reach it; a pod IP is routable cluster-wide by default. The
  policy needs a CNI that enforces it — where none is installed the object is
  accepted and ignored.
- **The ingress blocks `/actuator`.** Defence in depth; the application already
  restricts everything but the probes to an administrator. It also still
  blocks `/h2-console`, which no longer exists — H2 went with the migration to
  PostgreSQL, and with it an unauthenticated SQL console that was `permitAll`
  in `SecurityConfig` and enabled unconditionally. The rule costs nothing and
  covers an older image being rolled back into place.

## The database

A StatefulSet rather than a Deployment, deliberately: the volume *is* the
database, and a Deployment's rolling update would start a second pod against
the same claim. Two postmasters on one data directory is corruption, not
contention.

`PGDATA` points at a subdirectory of the mount rather than the mount itself.
The image only initialises a directory it created, and a fresh volume already
contains `lost+found` — without the subdirectory `initdb` refuses to run and
the pod crash-loops on first start.

The schema is applied by Flyway at application startup, and `ddl-auto` is
`validate`: a mapping that has drifted from the migrations stops the
application rather than being quietly patched at runtime.

## What is missing

- **Backups.** Nothing here backs the database up, and an untested restore is
  not a backup. This is the largest remaining gap.
- **Object storage for uploads** — the one thing still holding replicas at 1.
- **A PodDisruptionBudget** — meaningless at one replica; add it with the HPA.
