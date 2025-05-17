# ChatApp with AI Rooms 🦄

Welcome aboard! This README will have you chatting with AI in no time—super simple, super funky.

---

## ⚡ Prerequisites

* **Java 17+** installed
* **`lib/json-20231013.jar`** in your project’s `lib/` directory
* **LLaMA model file** (e.g. llama3.2:1b)
* **llama.cpp** repository cloned and built

---

## 1. Compile Your Chat App 🛠️

```bash
# In your project root (where .java files live):
javac -cp lib/json-20231013.jar Server.java Client.java
```

Outcome: `Server.class` and `Client.class` appear.

---

## 2. Fire Up the LLaMA AI Server 🦙

If you’ve got **ollama** installed and a model named `llama3.2:1b`, just run:

```bash
ollama run llama3.2:1b
```

Server URL: `http://localhost:11434/api/generate`

---

## 3. Launch the Chat Server 💻

Open a new terminal:

```bash
# Terminal #1:
java -cp .:lib/json-20231013.jar Server
```

You’ll see:

```
Server listening on port 8888
```

---

## 4. Spin Up a Chat Client 🖥️

Open another terminal:

```bash
# Terminal #2:
java -cp .:lib/json-20231013.jar Client
```

Follow prompts to **Login** or **Register**, then use `/join`, `/create`, etc. to chat.

---