#!/bin/bash
# Deploy studieplus-mcp to Google Cloud Run

# Configuration
PROJECT_ID="${GCP_PROJECT_ID:-your-project-id}"
REGION="${GCP_REGION:-europe-north1}"
SERVICE_NAME="studieplus-mcp"

# Check if credentials are set
if [ -z "$STUDIEPLUS_USERNAME" ] || [ -z "$STUDIEPLUS_PASSWORD" ]; then
    echo "Error: Set STUDIEPLUS_USERNAME and STUDIEPLUS_PASSWORD environment variables"
    echo "Example:"
    echo "  export STUDIEPLUS_USERNAME=your_username"
    echo "  export STUDIEPLUS_PASSWORD=your_password"
    exit 1
fi

# Build and deploy
echo "Building and deploying to Cloud Run..."
gcloud run deploy $SERVICE_NAME \
    --source . \
    --project $PROJECT_ID \
    --region $REGION \
    --platform managed \
    --allow-unauthenticated \
    --set-env-vars "STUDIEPLUS_USERNAME=$STUDIEPLUS_USERNAME,STUDIEPLUS_PASSWORD=$STUDIEPLUS_PASSWORD,STUDIEPLUS_SCHOOL=${STUDIEPLUS_SCHOOL:-TECHCOLLEGE},MCP_TRANSPORT=sse" \
    --memory 512Mi \
    --cpu 1 \
    --timeout 60 \
    --concurrency 10

echo ""
echo "Done! Your MCP server is available at the URL above."
echo "Use the /sse endpoint for MCP connections."
