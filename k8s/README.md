# Kubernetes manifests

```bash
kubectl create secret generic cde-platform-secrets \
  --from-literal=jwt-secret="$(openssl rand -base64 48)" \
  --from-literal=anthropic-api-key=""

kubectl apply -k k8s/
```

Edit the image registry in `kustomization.yaml` and the host in `ingress.yaml`
first; both carry placeholders.

---

## Read this before deploying anything real

**Data does not survive a pod restart.** The database is H2, held in the pod's
memory, with `ddl-auto: create-drop`. Every restart — a rolling update, a node
drain, an eviction, an OOM kill — destroys every project, document and
annotation. Nothing in these manifests can fix that; it is a property of the
application's configuration.

Deploy this for a demo or an evaluation. Do not put work in it that anyone
would mind losing, until PostgreSQL replaces H2.

## Why one replica

`replicas: 1` is not a placeholder to raise when traffic grows. Two things
break at two:

| | |
|---|---|
| **The database** | H2 lives in each pod's memory. A second replica gets a second, empty database — sign in against one pod and the next request lands on the other as an unknown user. |
| **The uploads volume** | `ReadWriteOnce`, so a second pod cannot mount it and sits `Pending` indefinitely. |

Neither failure is loud. The first presents as users being logged out at
random; the second as a deployment that never finishes. `hpa.yaml` exists and
is deliberately left out of `kustomization.yaml` for this reason — its header
lists what must change first.

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
- **The ingress blocks `/actuator` and `/h2-console`.** Defence in depth. The
  real problem is that `/h2-console` is `permitAll` in `SecurityConfig` and
  the console is enabled unconditionally in `application.yml`; that should be
  profile-gated in the application rather than relied on here.

## What is missing

- **PostgreSQL** — no manifest, because the application is not yet configured
  for it. This is the prerequisite for everything else on this list.
- **Object storage for uploads** — the second prerequisite for more than one
  replica.
- **A PodDisruptionBudget** — meaningless at one replica; add it with the HPA.
- **Backups** — nothing to back up while the database is in memory.
