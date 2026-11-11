#!/bin/sh
set -eu

# Development-only schema sync; never enable for an actual deployment.
if [ "${NOVA_DEV_SCHEMA_SYNC:-false}" = "true" ]; then
  echo "Development schema sync (NOVA_DEV_SCHEMA_SYNC=true). Not for production."
  npx prisma db push --skip-generate
else
  if [ ! -d prisma/migrations ]; then
    echo "Production startup refused: commit versioned Prisma migrations first." >&2
    exit 1
  fi
  npx prisma migrate deploy
fi

exec node dist/server.js
