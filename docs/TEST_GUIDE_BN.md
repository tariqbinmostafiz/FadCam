# FadCam (ফর্ক) — পরিবর্তন তালিকা ও টেস্ট গাইড

অ্যাপ আইডি: `com.fadcam.beta` (ডিবাগ বিল্ড)। লগ দেখতে পিসি/Termux-এ `adb logcat` বা অ্যাপের Debug Logging চালু করুন।

---
## ক) নতুন ব্যাচের পরিবর্তন (দৃশ্যমান)

### ১. ভিডিও ৬০ fps
- **কোথায়:** Settings → Video Recording → Frame Rate → `60 fps (experimental)`
- **কী বদলেছে:** ক্যামেরা নিজে ৬০ রিপোর্ট না করলেও তালিকায় ৬০ দেখায়। নির্বাচিত হলে `RecordingService` ক্যামেরাকে সরাসরি `[60,60]` ফ্রেমরেট রেঞ্জ চায়।
- **টেস্ট:**
  1. Back camera, FHD, ৩০ সেকেন্ড রেকর্ড করুন (প্রথমে ৩০ fps, পরে ৬০ fps)।
  2. লগ: `adb logcat | grep -i "forcing AE range"` → দেখা উচিত `forcing AE range [60,60]`।
  3. আসল ফ্রেমরেট যাচাই: ফাইল পিসিতে নিয়ে `ffprobe -v error -select_streams v -show_entries stream=avg_frame_rate,nb_frames -of default=nw=1 ফাইল.mp4`। (ফোনে MediaInfo অ্যাপও চলে।)
- **সম্ভাব্য ফল:** (ক) সত্যিকারের ৬০ fps হলে সফল। (খ) ক্যামেরা ৩০-এই থেকে যায় ও ভিডিও আসলে ৩০ fps হয়: Xiaomi HAL ৬০ লুকিয়ে রেখেছে। (গ) ক্যামেরা এরর/কালো স্ক্রিন: ৩০-এ ফিরুন।
- **আমাকে জানান:** ফল (ক/খ/গ), `ffprobe` আউটপুট, কোন রেজোলিউশনে চেষ্টা করেছেন।

### ২. স্ক্রিন রেকর্ড অডিও: "Both"
- **কোথায়:** Settings → Screen Recording → Audio Source → `Both (Mic + Device Audio)` (FadRec হোমের অডিও বাটনেও আছে, লেবেল `MIX`)।
- **কী বদলেছে:** মাইক ও ডিভাইস অডিও আলাদাভাবে ধরে একসাথে মিশিয়ে একটি অডিও ট্র্যাকে রাখে। ডিভাইস অডিও ধরা না গেলে নিজে মাইক-অনলিতে নামে।
- **টেস্ট:** অন্য অ্যাপে গান/ভিডিও চালিয়ে নিজে কথা বলতে বলতে ২০ সেকেন্ড স্ক্রিন রেকর্ড করুন। প্লেব্যাকে দুটোই শোনা উচিত।
- লগ: `adb logcat | grep "FadRec Both"` → `mic + device audio capture ready` দেখা উচিত।
- **জানা সীমা:** Android 10+ দরকার। কিছু অ্যাপ (DRM/কল অডিও) ডিভাইস অডিও ধরতে দেয় না, সেগুলোতে শুধু মাইক শোনা যাবে।
- **আমাকে জানান:** দুটো শব্দই এসেছে কিনা, কোন অ্যাপের অডিও, `FadRec Both` লগ।

### ৩. UI থেকে সরানো
Tutorial Videos সেকশন (YouTube বাটনসহ), "FadCam for iOS" কার্ড, "Write a Review" সারি। Watermark-এর ভুল "Coming Soon" ব্যাজও লুকানো হয়েছে (ফিচারটি আসলে কাজ করে)।
- **টেস্ট:** Settings স্ক্রল করে দেখুন ওগুলো নেই এবং অন্য কিছু ভাঙেনি। Watermark ট্যাপ করলে খোলে কিনা দেখুন।

---
## খ) আগের ব্যাচ (Phase 1) — নিরাপত্তা, রিলায়েবিলিটি, রুট

| # | কী | কীভাবে টেস্ট |
|---|---|---|
| ১ | Remote পাসওয়ার্ড হ্যাশ (PBKDF2) | Remote চালু করে লগইন করুন। লগ: `adb logcat \| grep "upgraded to PBKDF2"` (পুরনো পাসওয়ার্ড থাকলে প্রথম লগইনে) |
| ২ | লগইন লকআউট | ব্রাউজারে ৬ বার ভুল পাসওয়ার্ড দিন → "Too many attempts" (HTTP 429) |
| ৩ | নতুন পাসওয়ার্ড কমপক্ষে ৮ অক্ষর | ৮-এর কম দিয়ে সেট করতে গেলে আটকাবে |
| ৪ | ক্যামেরা রিকানেক্ট | রেকর্ড চলাকালীন অন্য ক্যামেরা অ্যাপ খুলে বন্ধ করুন। লগ: `became available -> immediate reconnect` |
| ৫ | কিলের পর রেজিউম | রেকর্ড চলাকালীন `su -c "kill -9 $(pidof com.fadcam.beta)"`। লগ: `auto-resuming`। (সার্ভিস রিস্টার্ট না হলে OEM কিলার; নিচের মডিউল দেখুন) |
| ৬ | RootTuner | রেকর্ড চলাকালীন `adb logcat -s RootTuner`: প্রতি ৩০ সেকেন্ডে `before=… after=…`। `before` আবার ডিফল্টে ফিরলে সিস্টেম মান বদলে দিচ্ছে |
| ৭ | Magisk/KernelSU মডিউল `fadcam-guardian` | ইনস্টল+রিবুটের পর রেকর্ড চালু রেখে অ্যাপ kill করুন বা ফোন রিবুট করুন। লগ ফাইল: `su -c "cat /data/local/tmp/fadcam_guardian.log"` |

**মডিউল বন্ধ রাখতে:** `su -c "touch /data/local/tmp/fadcam_guardian.disable"`
**রুট সুইচ বন্ধ:** অ্যাপ প্রেফারেন্স `root_mode_enabled=false` (UI টগল এখনও নেই)।

---
## গ) রিপোর্ট ফরম্যাট (কপি করে পাঠান)
```
ফোন/ROM:
টেস্ট নম্বর:
ফল: সফল / আংশিক / ব্যর্থ
কী দেখলাম:
লগ লাইন (প্রাসঙ্গিক ৫–১০ লাইন):
```
বিল্ড ফেইল করলে Actions → রান → Summary-র `Gradle error summary` টেক্সট পাঠান।

---
## ঘ) এখনও বাকি (Coming Soon)
Thermal Guardian, Scheduled Recording, Audio Vision, Profiles, এবং মিনি অ্যাপ: Compass, Sound Meter, Sensor Dashboard, Speedometer, Clinometer, Pedometer, Metal Detector, Parking Marker, QR Generator। এগুলো পরের ব্যাচগুলোতে।
