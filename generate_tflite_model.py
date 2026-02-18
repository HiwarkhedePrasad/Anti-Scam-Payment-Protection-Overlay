"""
AntiScam TFLite Model Generator — Run in Google Colab
=====================================================
Encodes the exact ScamJudge logistic regression weights into a real .tflite model.
The inference is mathematically identical: binary bag-of-words → sigmoid.

Usage (Colab):
  1. Upload this script or paste into a cell
  2. Run:  !pip install tensorflow numpy
  3. Run the script
  4. Download the generated `scam_detector.tflite` file
  5. Place it in: AntiScamApp/app/src/main/assets/scam_detector.tflite
"""

import numpy as np
import json

try:
    import tensorflow as tf
    print(f"TensorFlow version: {tf.__version__}")
except ImportError:
    raise ImportError("Run: !pip install tensorflow")

# ═══════════════════════════════════════════════════════════════════════════════
# VOCABULARY + WEIGHTS — exact copy from ScamJudge.kt
# ═══════════════════════════════════════════════════════════════════════════════

INTERCEPT = -0.9924

WEIGHTS = {
    # HIGH-RISK SCAM WORDS (from trained model)
    "request": 1.3635,
    "com": 1.3262,
    "tinyurl": 1.322,
    "mins": 1.21,
    "bit": 1.1688,
    "ly": 1.1688,
    "pin": 1.1162,
    "enter": 1.0591,
    "approve": 1.0405,
    "receive": 1.0039,
    "collect": 0.8111,
    "pending": 0.7441,
    "avoid": 0.6033,
    "acc0unt": 0.5881,
    "kyc": 0.577,
    "refund": 0.5734,
    "cr3dit": 0.5659,
    "confirm": 0.5505,
    "v3rify": 0.5457,
    "support": 0.5413,
    "aapka": 0.5216,
    "helpline": 0.5201,
    "l1nk": 0.5114,
    "pay": 0.4953,
    "today": 0.4869,
    "urgent": 0.4808,
    "verification": 0.4748,
    "bl0cked": 0.4742,
    "pan": 0.4707,
    "verify": 0.4681,
    "account": 0.4671,
    "offer": 0.4562,
    "unknown": 0.4411,
    "aadhaar": 0.436,
    "ybl": 0.4298,
    "claim": 0.4233,
    "alert": 0.4223,
    "unpaid": 0.4204,
    "immediately": 0.4196,
    "9876xxxxxx": 0.4047,
    "blocked": 0.4034,
    # ADDITIONAL SCAM PATTERNS (UPI/payment fraud)
    "collecting": 0.95,
    "requesting": 0.90,
    "requested": 0.85,
    "decline": 0.70,
    # OTP / PIN phishing
    "otp": 1.05,
    "password": 0.90,
    "cvv": 1.10,
    "expiry": 0.75,
    "mpin": 1.00,
    "passcode": 0.85,
    "authentication": 0.60,
    # Fake reward / lottery scams
    "congratulations": 1.15,
    "winner": 1.10,
    "won": 0.95,
    "lottery": 1.20,
    "prize": 1.05,
    "cashback": 0.80,
    "reward": 0.75,
    "bonus": 0.70,
    "lucky": 0.90,
    "selected": 0.65,
    # Urgency / pressure tactics
    "expire": 0.70,
    "expiring": 0.75,
    "expired": 0.65,
    "suspend": 0.80,
    "suspended": 0.85,
    "deactivate": 0.80,
    "deactivated": 0.85,
    "inactive": 0.65,
    "limited": 0.50,
    "hurry": 0.70,
    "fast": 0.40,
    "quick": 0.35,
    "deadline": 0.55,
    "warning": 0.50,
    # Fake customer care / tech support
    "customer": 0.45,
    "care": 0.35,
    "executive": 0.50,
    "representative": 0.45,
    "toll": 0.55,
    "free": 0.40,
    "complaint": 0.40,
    "grievance": 0.45,
    # Link / URL scam indicators
    "click": 0.90,
    "link": 0.75,
    "http": 0.85,
    "https": 0.60,
    "www": 0.55,
    "website": 0.50,
    "url": 0.65,
    "download": 0.70,
    "install": 0.65,
    "app": 0.30,
    # Fake bank / identity impersonation
    "rbi": 0.55,
    "government": 0.50,
    "ministry": 0.55,
    "income": 0.40,
    "tax": 0.45,
    "it": 0.10,
    "department": 0.40,
    "officer": 0.50,
    "police": 0.55,
    # Money transfer scam words
    "transfer": 0.50,
    "send": -0.30,
    "amount": 0.30,
    "money": 0.45,
    "payment": 0.40,
    "upi": 0.25,
    "wallet": 0.35,
    "paytm": 0.15,
    "phonepe": 0.15,
    "gpay": 0.15,
    # Leet speak / adversarial spellings
    "p1n": 1.10,
    "0tp": 1.05,
    "urgnt": 0.80,
    "blcked": 0.75,
    "verifiy": 0.70,
    "acccount": 0.65,
    "suspendd": 0.80,
    # Hindi/Hinglish scam phrases
    "kare": 0.55,
    "karein": 0.55,
    "turant": 0.65,
    "jaldi": 0.60,
    "abhi": 0.50,
    "nahi": 0.30,
    "band": 0.55,
    "paisa": 0.40,
    "khata": 0.45,
    "daalein": 0.50,
    # SAFE WORDS (negative weights)
    "ref": -0.4013,
    "800": -0.4189,
    "feb": -0.4261,
    "okicici": -0.4433,
    "okhdfcbank": -0.4447,
    "560": -0.4555,
    "successfully": -0.4828,
    "id": -0.4928,
    "recharge": -0.5128,
    "000": -0.542,
    "received": -0.5846,
    "credited": -0.5946,
    "balance": -0.6168,
    "icici": -0.6197,
    "hdfc": -0.6239,
    "sbi": -0.6989,
    "debited": -0.9346,
    "paid": -1.2021,
    # Additional safe words
    "statement": -0.45,
    "transaction": -0.35,
    "successful": -0.50,
    "completed": -0.45,
    "confirmed": -0.40,
    "deposited": -0.55,
    "withdrawn": -0.40,
    "atm": -0.35,
    "neft": -0.40,
    "imps": -0.40,
    "rtgs": -0.40,
    "avl": -0.35,
    "bal": -0.30,
    "axis": -0.50,
    "kotak": -0.50,
    "bob": -0.45,
    "pnb": -0.45,
    "canara": -0.45,
    "union": -0.40,
    # SYSTEM LOCK SCREEN SAFE WORDS
    "draw": -0.80,
    "pattern": -0.90,
    "fingerprint": -0.95,
    "face": -0.85,
    "unlock": -0.70,
    "emergency": -0.75,
    "recognised": -0.60,
    "recognized": -0.60,
    "charging": -0.50,
    "swipe": -0.55,
}

# ═══════════════════════════════════════════════════════════════════════════════
# BUILD THE TFLITE MODEL
# ═══════════════════════════════════════════════════════════════════════════════

# Sort vocabulary for deterministic index ordering
vocab_sorted = sorted(WEIGHTS.keys())
vocab_size = len(vocab_sorted)
word_to_index = {w: i for i, w in enumerate(vocab_sorted)}

print(f"Vocabulary size: {vocab_size} features")

# Build weight vector in vocab order
weight_vector = np.array([WEIGHTS[w] for w in vocab_sorted], dtype=np.float32)

# Create a minimal Keras model: single Dense layer with sigmoid = logistic regression
model = tf.keras.Sequential([
    tf.keras.layers.InputLayer(input_shape=(vocab_size,)),
    tf.keras.layers.Dense(1, activation='sigmoid')
])

# Build the model so weights are initialized
model.build((None, vocab_size))

# Set the exact weights: kernel = weight_vector (column), bias = intercept
model.layers[0].set_weights([
    weight_vector.reshape(vocab_size, 1),  # kernel shape: (features, 1)
    np.array([INTERCEPT], dtype=np.float32)  # bias shape: (1,)
])

# Verify with a quick test
test_input = np.zeros((1, vocab_size), dtype=np.float32)
# Activate "pin", "enter", "collect" (common scam words)
for test_word in ["pin", "enter", "collect", "request"]:
    if test_word in word_to_index:
        test_input[0, word_to_index[test_word]] = 1.0

pred = model.predict(test_input, verbose=0)[0][0]
print(f"Test prediction (pin+enter+collect+request): {pred*100:.1f}% scam")

# Also test a safe message
test_safe = np.zeros((1, vocab_size), dtype=np.float32)
for test_word in ["credited", "successfully", "balance", "hdfc"]:
    if test_word in word_to_index:
        test_safe[0, word_to_index[test_word]] = 1.0

pred_safe = model.predict(test_safe, verbose=0)[0][0]
print(f"Test prediction (credited+successfully+balance+hdfc): {pred_safe*100:.1f}% scam")

# ═══════════════════════════════════════════════════════════════════════════════
# CONVERT TO TFLITE
# ═══════════════════════════════════════════════════════════════════════════════

converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

tflite_path = "scam_detector.tflite"
with open(tflite_path, "wb") as f:
    f.write(tflite_model)

print(f"\n✅ Model saved: {tflite_path} ({len(tflite_model)} bytes)")

# ═══════════════════════════════════════════════════════════════════════════════
# SAVE VOCABULARY INDEX (for embedding in ScamJudge.kt)
# ═══════════════════════════════════════════════════════════════════════════════

vocab_json_path = "vocab_index.json"
with open(vocab_json_path, "w") as f:
    json.dump(word_to_index, f, indent=2)

print(f"✅ Vocabulary index saved: {vocab_json_path}")

# ═══════════════════════════════════════════════════════════════════════════════
# VERIFY TFLITE MODEL MATCHES KERAS MODEL
# ═══════════════════════════════════════════════════════════════════════════════

interpreter = tf.lite.Interpreter(model_content=tflite_model)
interpreter.allocate_tensors()

input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print(f"\nTFLite input shape:  {input_details[0]['shape']}")
print(f"TFLite output shape: {output_details[0]['shape']}")

# Verify scam test
interpreter.set_tensor(input_details[0]['index'], test_input)
interpreter.invoke()
tflite_pred = interpreter.get_tensor(output_details[0]['index'])[0][0]
print(f"\nTFLite scam test:  {tflite_pred*100:.1f}% (Keras: {pred*100:.1f}%)")

# Verify safe test
interpreter.set_tensor(input_details[0]['index'], test_safe)
interpreter.invoke()
tflite_safe = interpreter.get_tensor(output_details[0]['index'])[0][0]
print(f"TFLite safe test:  {tflite_safe*100:.1f}% (Keras: {pred_safe*100:.1f}%)")

print(f"\n{'='*60}")
print("NEXT STEPS:")
print("1. Download 'scam_detector.tflite' from the file browser (left panel)")
print("2. Place it in: AntiScamApp/app/src/main/assets/scam_detector.tflite")
print(f"{'='*60}")
