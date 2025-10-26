# 📱 Object Detection App

Android application for **real-time object detection** using a custom **YOLO model** and cloud integration via **Supabase**.  
The app detects numeric characters (and other trained classes) directly from the camera feed and uploads cropped detection results to a Supabase database.

---

## 🚀 Features

- 🔍 **Real-time object detection** using TensorFlow Lite (YOLOv8/YOLOv10).
- 📸 **CameraX live preview** with bounding boxes.
- ☁️ **Supabase integration** — sends detection data (label, confidence, timestamp, and cropped image).
- ⚡ Optimized with frame throttling and hash-checks to prevent duplicate uploads.
- 🧠 Supports both **Float16** and **Float32** YOLO models.

---

## 🧩 Tech Stack

| Component | Technology |
|------------|-------------|
| ML Model | YOLOv8/YOLOv10 (TensorFlow Lite) |
| Camera | AndroidX CameraX |
| Cloud Storage | Supabase REST API |
| Language | Kotlin |
| UI | Jetpack Compose |
| Data Format | JSON + Base64 Encoded Images |

---

## 🏗️ Architecture Overview



┌────────────────────┐
│ Android CameraX │
│ (real-time frames) │
└─────────┬──────────┘
│
▼
┌────────────────────┐
│ YOLO.tflite model │
│ TensorFlow Lite │
└─────────┬──────────┘
│ Detected objects
▼
┌────────────────────┐
│ Kotlin logic │
│ (cropping, encode) │
└─────────┬──────────┘
│ JSON + Base64
▼
┌────────────────────┐
│ Supabase REST API │
│ (PostgreSQL table) │
└────────────────────┘


---

## 🧰 Installation

### 1️⃣ Clone the repository
```bash
git clone https://github.com/Kaspars-R/object-detection-app.git
cd object-detection-app


2️⃣ Open in Android Studio

File → Open → select the object-detection-app folder

Wait for Gradle sync to complete

3️⃣ Add Supabase credentials

In local.properties or directly in BuildConfig:

const val SUPABASE_URL = "https://your-instance.supabase.co"
const val SUPABASE_KEY = "your-service-key"

4️⃣ Build and run

Connect an Android device (with camera)

Press ▶️ Run


🧪 Supabase Table Schema
Column	Type	Description
id	bigint	Primary key
label	text	Detected class label
confidence	float	Detection confidence
timestamp	bigint	Unix timestamp
left / top / right / bottom	float	Bounding box coordinates
device_id	text	Android device model
image_base64	text	Base64-encoded cropped image
⚙️ Performance Optimizations

Skips duplicate detections using hash-based filtering.

Frame sending limited to once per second (sendMinIntervalMs).

Uses scaled Bitmap copies to reduce memory footprint.

🧑‍💻 Author

Kaspars R.
Rēzekne, 2025


<img width="727" height="260" alt="image" src="https://github.com/user-attachments/assets/670a14a1-1fca-40ca-9b14-ef752725ed11" />



