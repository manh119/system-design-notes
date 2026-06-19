
### Vấn đề : khi có nhiều step đợi nhau -> mỗi step có thể chết, có thể retry, có thể phải rollback 

- ví dụ step đặt hàng -> thanh toán service -> ship hàng service -> thông báo service 

#### giải pháp 1 

- thường dùng : gọi API /process -> sau đó trong api này xử lý hết các call API khác 
- với mỗi bước thành công thì lưu vào database (PENDING, FAIL, SUCCESS, ...) và thực hiện bước tiếp theo - có trường status trong db
- bước nào quan trọng thì chạy cronjob để retry, còn không thì trả về lỗi cho người dùng để người dùng retry 
- Multi-step process (quy trình nhiều bước) là:
	Một request của user không thể hoàn thành bằng 1 thao tác đơn lẻ, mà phải đi qua nhiều bước, mỗi bước gọi service khác nhau, có thể chạy lâu (hours / days) và có thể fail ở bất kỳ đâu.

![[Pasted image 20260619225259.png]]




#### giải pháp 2 : event sourcing (lưu chuỗi event thay vì chỉ lưu mỗi trạng thái) nên có thể replay

- mỗi service thực hiện xong thì bắn event vào kafka (thành công hoặc thất bại)
- các service khác lắng nghe thì thực hiện bước tiếp theo hoặc đền bù tương ứng
- giống kiểu saga Choreography (event-driven):
	- Mỗi service phát event
	- Service khác nghe và làm tiếp

- saga choreography chỉ là 1 cách dùng event sourcing 
- Event sourcing không phải chỉ là “bắn event”, cốt lõi là:
	- durable log
	- event là source of truth
	- replay để rebuild *state*

AMZ cảnh báo saga pattern : 

![[Pasted image 20260619225630.png]]

#### giải pháp 3 : dùng sẵn framework 

- Workflow Systems (Temporal - Netflix, Stripe, Uber, AWS step function) 
- có sẵn retry + đợi event từ bước khác + sẵn lưu even sourcing để có thể replay + monitor đẹp 
- mình chỉ cần đảm bảo mỗi step là idempotent 
- code lỗi -> fĩx code đẩy lên -> chúng ta nên đối soát, hoàn tiền, hay tiếp theo như nào, dữ liệu db đang ko đồng bộ ?? xóa dữ liệu nào + sửa dữ liệu nào ?? 
- -> với temporal thì đẩy lên thì nó tự resume và chạy tiếp 
- nếu nhiều server -> mỗi service có thể handle 1 step, xịn vl - mà vẫn đợi tuần tự 




> [! 10 Interview question]
> 1. Tại sao 