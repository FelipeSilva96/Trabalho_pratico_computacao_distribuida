import hashlib
import io
import os

from PIL import Image

CLASSES = ["battery", "glass", "metal", "organic", "paper", "plastic"]


class WasteClassifier:
    def __init__(self):
        self.model_path = os.getenv(
            "MODEL_PATH",
            "/app/models/waste_mobilenet_v3_small.pt",
        )
        self.allow_demo = os.getenv("ALLOW_DEMO_CLASSIFIER", "true").lower() == "true"
        self.model = None
        self.transform = None
        self.device = "cpu"
        self.load_error = None

        self._try_load_model()

    @property
    def model_loaded(self):
        return self.model is not None

    @property
    def demo_mode(self):
        return self.model is None and self.allow_demo

    def _try_load_model(self):
        if not os.path.exists(self.model_path):
            self.load_error = "model file not found"
            return

        try:
            import torch
            from torch import nn
            from torchvision import models, transforms

            self.device = "cuda" if torch.cuda.is_available() else "cpu"

            model = models.mobilenet_v3_small(weights=None)
            in_features = model.classifier[3].in_features
            model.classifier[3] = nn.Linear(in_features, len(CLASSES))

            checkpoint = torch.load(self.model_path, map_location=self.device)
            if isinstance(checkpoint, dict) and "model_state_dict" in checkpoint:
                state_dict = checkpoint["model_state_dict"]
            else:
                state_dict = checkpoint

            model.load_state_dict(state_dict)
            model.eval()
            model.to(self.device)

            self.transform = transforms.Compose([
                transforms.Resize(256),
                transforms.CenterCrop(224),
                transforms.ToTensor(),
                transforms.Normalize(
                    mean=[0.485, 0.456, 0.406],
                    std=[0.229, 0.224, 0.225],
                ),
            ])

            self.model = model
        except Exception as exc:
            self.model = None
            self.transform = None
            self.load_error = str(exc)

    def predict(self, image_bytes):
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

        if self.model is not None:
            return self._predict_model(image)

        if self.allow_demo:
            return self._predict_demo(image_bytes)

        return {
            "label": "",
            "confidence": 0.0,
            "status": "MODEL_NOT_LOADED",
            "demo_mode": False,
        }

    def _predict_model(self, image):
        import torch

        tensor = self.transform(image).unsqueeze(0).to(self.device)

        with torch.no_grad():
            logits = self.model(tensor)
            probabilities = torch.softmax(logits, dim=1)
            confidence, index = torch.max(probabilities, dim=1)

        return {
            "label": CLASSES[index.item()],
            "confidence": float(confidence.item()),
            "status": "OK",
            "demo_mode": False,
        }

    def _predict_demo(self, image_bytes):
        digest = hashlib.sha256(image_bytes).digest()
        index = digest[0] % len(CLASSES)
        confidence = 0.50 + (digest[1] / 255.0) * 0.35

        return {
            "label": CLASSES[index],
            "confidence": round(confidence, 4),
            "status": "DEMO",
            "demo_mode": True,
        }
