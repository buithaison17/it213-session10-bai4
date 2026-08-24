# BÁO CÁO PHÂN TÍCH CƠ CHẾ COST TRACKING & GIÁM SÁT LATENCY TRÊN LANGFUSE
## DỰ ÁN: RIKKEI INTELLIGENT BANKING & ASSISTANT SUITE (RikkeiPay)
**Phân hệ:** Trợ lý ảo ngân hàng thông minh (RikkeiPay Assistant)  
**Bài tập:** Bài 4 - Giám Sát Chi Phí & Phân Tích Latency

---

## 1. CƠ CHẾ TÍNH TOÁN TOKEN & CHI PHÍ (TOKEN & COST TRACKING)

Hệ thống LLMOps Langfuse cho phép kiểm soát chi phí tự động và chuẩn xác theo thời gian thực đối với từng truy vấn AI.

### 1.1. Cơ chế tự động đếm Token từ Spring AI
1. **Trích xuất Metadata từ LLM Response:**
   - Khi ứng dụng Spring AI gọi API của nhà cung cấp LLM (OpenAI, Gemini, Anthropic, DeepSeek...), response trả về luôn kèm theo đối tượng telemetry chuẩn `Usage` metadata:
     - `prompt_tokens` (Input tokens)
     - `completion_tokens` (Output tokens)
     - `total_tokens` (Tổng số tokens)
2. **Ánh xạ tự động vào Langfuse Generation Object:**
   - Khi tích hợp Langfuse SDK / Callback Listener trong Spring AI, SDK tự động trích xuất các trường `Usage` này và đính kèm vào node `Generation` tương ứng trong Trace.
   - Nhờ đó, kỹ sư không cần tự đếm token bằng tokenizer cục bộ (như tiktoken) mà vẫn đảm bảo số liệu trùng khớp 100% với hóa đơn API của nhà cung cấp.

---

### 1.2. Cơ chế ánh xạ bảng giá & Tính toán chi phí
- Langfuse duy trì danh mục bảng giá chuẩn (Built-in Model Price List).
- Khi nhận được tên mô hình (`model_name`) từ trace, Langfuse tự động nhân số lượng token với đơn giá cấu hình theo công thức:

$$\text{Total Cost} = (\text{Input Tokens} \times \text{Input Price}) + (\text{Output Tokens} \times \text{Output Price})$$

---

### 1.3. Hướng dẫn thiết lập bảng giá tùy chỉnh (Custom Model Prices) trên Langfuse Dashboard
Đối với các mô hình mới hoặc giá ưu đãi doanh nghiệp (ví dụ: `gemini-2.5-flash`, `deepseek-v3`, mô hình Self-hosted vLLM):

1. **Bước 1: Truy cập mục quản trị Model**
   - Đăng nhập vào Langfuse Dashboard $\rightarrow$ Chọn Project của **RikkeiPay** $\rightarrow$ Vào menu **Settings** $\rightarrow$ Chọn tab **Models**.
2. **Bước 2: Tạo định danh bảng giá mới (+ New Model)**
   - Nhấn nút **Add Model Definition**.
3. **Bước 3: Điền các thông số chi phí:**
   - **Model Name / Match Pattern:** Nhập tên định danh chính xác hoặc Regex Pattern (Ví dụ: `gemini-2.5-flash` hoặc `(?i)deepseek-v3.*`).
   - **Unit:** Chọn đơn vị tính toán (`TOKENS` hoặc `CHARACTERS`).
   - **Input Cost (USD per 1k / 1M tokens):** Điền đơn giá cho token đầu vào (Prompt).
   - **Output Cost (USD per 1k / 1M tokens):** Điền đơn giá cho token đầu ra (Completion).
   - **Start Date:** Thiết lập ngày bắt đầu có hiệu lực (cho phép cập nhật bảng giá theo từng đợt khuyến mãi/giảm giá của nhà cung cấp mà không làm sai lệch chi phí lịch sử).
4. **Bước 4: Lưu & Kiểm tra:**
   - Nhấn **Save**. Ngay khi có Trace mới gửi lên mang `model` tương ứng, Langfuse sẽ tự động ánh xạ và hiển thị trường `calculated_total_cost` trên Dashboard.

---

## 2. HƯỚNG DẪN PHÂN TÍCH BIỂU ĐỒ LATENCY ĐỂ XÁC ĐỊNH BOTTLENECK LUỒNG RAG

Trong kiến trúc RAG của RikkeiPay, một cuộc gọi hoàn chỉnh bao gồm 2 công đoạn chính: **Vector DB Search (Retrieval)** và **LLM Inference (Generation)**.

```text
[Trace: rikkeipay-rag-query] -----------------------------------------------------> (Total Latency)
   |-- [Span: vector-retrieval] ---------> (Vector DB Query Duration)
   |-- [Generation: llm-generation] ---------------------------------------------> (LLM Inference Duration)
```

### 2.1. Phân rã cấu trúc Trace (Spans Breakdown)
Để phân tích chính xác, hệ thống phải được bóc tách thành các Spans rõ ràng:
- **Span 1: `embedding-query` & `vector-retrieval`:** Đo lường thời gian tạo embedding vector cho câu hỏi và truy vấn Top-K documents từ Vector Database (Milvus/Pgvector/Pinecone).
- **Span 2: `llm-generation`:** Đo lường thời gian LLM tiếp nhận context đã ghép và sinh phản hồi.

---

### 2.2. Phương pháp xác định Bottleneck qua Waterfall & Metrics

| Thành phần kiểm tra | Chỉ số theo dõi chính | Dấu hiệu nhận biết Bottleneck | Nguyên nhân & Hướng xử lý tối ưu |
| :--- | :--- | :--- | :--- |
| **Bước 1: Vector DB (Retrieval)** | - **Span Duration (ms)**<br>- **Document Count**<br>- **Similarity Score** | - Thanh `retrieval` chiếm **> 50% - 70%** tổng thời gian Trace.<br>- Độ trễ tăng đột biến khi dữ liệu vector lớn. | **Nguyên nhân:** Thiếu Index (HNSW/IVF), kích thước Collection quá lớn hoặc khoảng cách mạng tới Vector DB xa.<br>**Xử lý:** Cache embedding câu hỏi thường gặp; tinh chỉnh tham số `M`/`efConstruction`; bật Partitioning. |
| **Bước 2: LLM Generation** | - **TTFT (Time To First Token)**<br>- **Generation Duration**<br>- **Tokens/second (Throughput)** | - TTFT kéo dài **> 2 - 3 giây**.<br>- Thanh `llm-generation` dài áp đảo toàn bộ trace.<br>- Output token quá dài. | **Nguyên nhân:** Context prompt quá dài, model kích thước lớn, nghẽn tải hoặc rate-limit từ nhà cung cấp API.<br>**Xử lý:** Bật Streaming SSE (`Flux<ChatResponse>`) giảm TTFT; rút gọn context; chuyển sang model tốc độ cao (Gemini Flash). |

---

### 2.3. Quy trình 3 bước chẩn đoán trên Langfuse Dashboard
1. **Bước 1: Quan sát Traces Table & Latency Distribution:**
   - Xem đồ thị phân phối độ trễ **p50, p90, p99**. Nếu p99 tăng vọt bất thường, lọc các trace có độ trễ cao nhất để mổ xẻ.
2. **Bước 2: Phân tích Gantt Chart / Waterfall View:**
   - Mở chi tiết Trace bị chậm, quan sát thanh timeline của từng Span con:
     - Nếu thanh `retrieval` dài bất thường $ightarrow$ Nghẽn tại Vector DB.
     - Nếu thanh `generation` dài $ightarrow$ Tiếp tục kiểm tra chỉ số **TTFT** và **Output Token Count**.
3. **Bước 3: Đối chiếu tương quan giữa Cost & Latency:**
   - Kiểm tra số lượng input token nạp vào LLM. Nếu input token quá lớn do nhồi nhét quá nhiều tài liệu RAG dư thừa, đây chính là nguyên nhân kép làm tăng cả chi phí lẫn thời gian sinh văn bản của LLM.