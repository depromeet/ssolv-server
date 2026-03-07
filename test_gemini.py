
import requests
import json

url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=AIzaSyDy5E4hnsylsmun7MIXDfHNov9Y3Fwblxo"
headers = {"Content-Type": "application/json"}
data = {
    "contents": [{
        "parts": [{"text": "Hello!"}]
    }]
}

try:
    response = requests.post(url, headers=headers, json=data, timeout=30)
    print(f"Status Code: {response.status_code}")
    print("Response Body:")
    print(json.dumps(response.json(), indent=2, ensure_ascii=False))
except Exception as e:
    print(f"Error: {e}")
