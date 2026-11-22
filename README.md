# SAAYA

### Beyond SOS. Towards Intelligent Safety.

**SAAYA is a cloud-based women's safety and emergency response platform that connects journey-based protection, real-time emergency communication, secure evidence management, and domain-specific AI-powered incident intelligence in a single system.**

> Built for **Lean In Hacks 8.0**

---

## 1. Overview

Most women's safety apps rely on a single mechanism: press SOS, and contacts get an alert. That covers only one moment in a much longer process.

SAAYA treats safety as a **connected journey**: before an incident (prevention), during it (protection), and after it (documentation and understanding).

| Stage | Focus | What SAAYA provides |
| --- | --- | --- |
| **Before** | Prevention | Journey monitoring, location awareness, trusted contacts |
| **During** | Protection | SOS, emergency escalation, notifications, real-time audio/video |
| **After** | Documentation | Evidence collection, secure storage, organization |
| **Intelligence** | Understanding | AI-assisted classification, summarization, timelines, information extraction |

The key differentiator is the link between the **immediate emergency-response system** and the **post-incident evidence-intelligence system**.

---

## 2. Key Features

- **Journey-based safety:** start a journey, keep trusted contacts connected with live location, and trigger SOS directly from it.
- **SOS and escalation:** push notifications and location sharing to emergency contacts, with real-time communication available.
- **Evidence management:** upload images, video, audio, documents, and written notes in one place.
- **Evidence Intelligence:** turns unstructured evidence into structured output (see below).

---

## 3. Evidence Intelligence

Storing evidence is not enough. The Evidence Intelligence layer organizes it into:

- Incident classification
- Contextual summary
- Timeline generation
- Information extraction

Audio is transcribed with Whisper, and images, video, and documents are processed to extract context before analysis.

> **Important:** AI-generated summaries and classifications are decision-support aids. They are **not** verified facts and never replace the original evidence, which is always preserved and presented separately.

---

## 4. Adaption AI Integration

A general-purpose LLM can summarize text, but SAAYA needs something more specialized: understanding incident-related evidence, classifying situations, extracting relevant details, and producing **structured** output. We used **Adaption AI** to explore domain-specific model adaptation rather than relying entirely on generic models.

### 4.1 Dataset Engineering

We built a dataset named **`SAAYA 1034 validated evidence reports (augmented)`**.

| Item | Detail |
| --- | --- |
| Source reports | 1,034 validated evidence reports |
| Dataset rows (Adaption dashboard) | 2,064 |
| Contents | Original prompts, original completions, enhanced prompts, enhanced completions, augmented examples |

The 2,064 rows are the rows shown in the dashboard after augmentation. They do not represent 2,064 independently collected incidents.

<img width="353" height="260" alt="image" src="https://github.com/user-attachments/assets/381611e0-d75c-472c-ae98-3b4ad90f4db3" />

### 4.2 AutoScientist

We explored Adaption AI's **AutoScientist** workflow as part of our model experimentation, supporting our dataset-driven development and instruction-tuning work.

### 4.3 Base Model and Training

| Item | Detail |
| --- | --- |
| Base model | **Qwen 3.5 4B** |
| Approach | Instruction tuning on SAAYA-specific data |
| Monitored metrics | Training loss, validation loss, learning rate, gradient norm |

Workflow: select the base model → prepare the dataset → configure instruction tuning → run experiments → monitor metrics → explore model weight exports.

Training metrics help us understand optimization behaviour. They do not by themselves establish model accuracy or real-world reliability, and we do not claim they do.

<img width="959" height="539" alt="image" src="https://github.com/user-attachments/assets/34bdf519-baba-4154-bcd7-3d8f2d2cbfdd" />

### 4.4 Status

Dataset preparation and instruction-tuning experiments are **completed**. Final production integration of the exported model and measured performance evaluation are **in progress**.

> Cloudflare Workers AI and the Adaption-trained model are **separate AI components**. They are not the same model or service.

---

## 5. Security and Privacy

SAAYA handles sensitive incident and location information, so privacy is a core design requirement: secure authentication, restricted access to evidence, protected uploads, access controls for trusted contacts, no secrets committed to the repository, and clear separation of original evidence from AI-generated analysis.

---

## 6. Future Scope

- Complete production integration of the Adaption-adapted model
- Quantitative evaluation of classification, summarization, and extraction quality
- Expanded and more diverse training data
- Multilingual support
- Deeper integration with local emergency services
- Configurable evidence retention and enhanced privacy controls

---

<p align="center"><b>SAAYA</b> — Beyond SOS. Towards Intelligent Safety.</p>
