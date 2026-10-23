#!/usr/bin/env bash
# Deploy the caption service to Google Cloud Run.
#
# Prereqs (one time):
#   1. Install the gcloud CLI: https://cloud.google.com/sdk/docs/install
#   2. gcloud auth login
#   3. gcloud config set project YOUR_PROJECT_ID
#   4. Billing must be enabled on the project.
#
# Usage:
#   ./deploy.sh                      # generates a random API key and prints it
#   API_KEY=my-secret ./deploy.sh    # use your own key
set -euo pipefail

SERVICE_NAME="${SERVICE_NAME:-yt-caption-service}"
REGION="${REGION:-us-east1}"

PROJECT_ID="$(gcloud config get-value project 2>/dev/null)"
if [[ -z "${PROJECT_ID}" ]]; then
  echo "No gcloud project set. Run: gcloud config set project YOUR_PROJECT_ID" >&2
  exit 1
fi

if [[ -z "${API_KEY:-}" ]]; then
  API_KEY="$(openssl rand -hex 24)"
  echo "Generated API key (save this, you need it in the mobile app): ${API_KEY}"
fi

echo "Enabling required Google Cloud APIs..."
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com iam.googleapis.com

# Dedicated service account, used for both the build and the running service.
# This avoids depending on the project's default compute service account,
# which may be missing or deleted.
SA_NAME="${SA_NAME:-yt-caption-runner}"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

if ! gcloud iam service-accounts describe "${SA_EMAIL}" >/dev/null 2>&1; then
  echo "Creating service account ${SA_EMAIL}..."
  gcloud iam service-accounts create "${SA_NAME}" --display-name "Caption service runner"
  echo "Waiting for the service account to propagate..."
  sleep 20
fi

echo "Granting build and runtime roles to ${SA_EMAIL}..."
for ROLE in roles/cloudbuild.builds.builder roles/logging.logWriter \
            roles/artifactregistry.writer roles/storage.objectAdmin; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" --role="${ROLE}" \
    --condition=None --quiet >/dev/null
done

echo "Building and deploying ${SERVICE_NAME} to ${REGION} in project ${PROJECT_ID}..."
# --allow-unauthenticated makes the URL reachable from a phone without Google
# credentials; the app-level X-API-Key check in main.py is what protects it.
gcloud run deploy "${SERVICE_NAME}" \
  --source . \
  --region "${REGION}" \
  --service-account "${SA_EMAIL}" \
  --build-service-account "projects/${PROJECT_ID}/serviceAccounts/${SA_EMAIL}" \
  --allow-unauthenticated \
  --memory 2Gi \
  --cpu 2 \
  --timeout 300 \
  --concurrency 4 \
  --min-instances 0 \
  --max-instances 3 \
  --set-env-vars "API_KEY=${API_KEY}"

URL="$(gcloud run services describe "${SERVICE_NAME}" --region "${REGION}" --format='value(status.url)')"
echo
echo "Deployed: ${URL}"
echo "Health:   curl ${URL}/health"
echo "Docs:     ${URL}/docs"
