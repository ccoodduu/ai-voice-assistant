#!/bin/bash
set -e

echo "=== Installing Tailscale ==="
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up

echo "=== Installing Docker ==="
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

echo "=== Cloning repo ==="
git clone https://github.com/ccoodduu/ai-voice-assistant.git
cd ai-voice-assistant
git checkout feature/webrtc-transport

echo "=== Creating .env file ==="
cat > .env << 'EOF'
GOOGLE_API_KEY=your-api-key-here
STUDIEPLUS_USERNAME=your-username
STUDIEPLUS_PASSWORD=your-password
EOF

echo "=== Setup complete! ==="
echo ""
echo "Next steps:"
echo "1. Edit .env with your actual credentials: nano .env"
echo "2. Log out and back in (for docker group)"
echo "3. Run: docker compose -f docker-compose.prod.yml pull"
echo "4. Run: docker compose -f docker-compose.prod.yml up -d"
echo ""
echo "Your Tailscale IP:"
tailscale ip -4
