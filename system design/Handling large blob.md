
![[Pasted image 20260619224411.png]]

### Vấn đề của pre-sign URL : server ko biết k/hang đã upload xong hay chưa, success/fail, upload đến đâu rồi + bảo mật, sợ upload lên s3 linh tinh 

- set content-length range, content-type, expire upload 
- khi upload xong thì s3 bắn noti cho service 
- cron job để xóa file upload thành công nhưng ko dùng #todo 




### Vấn đề : upload lại khi file lớn (upload dở nhưng die)

- multi-part upload để có thể upload tiếp từ phần đang upload 
- thế thì có chiếm RAM của người dùng nhiều ko?
- Ví dụ upload video lên youtube ? hay upload video lên fb new feed? Và để có thể upload tiếp từ phần đang upload thì app của người dùng cần hoạt động đúng ko? và HTTP bình thường tự handle upload lại dựa trên TCP đúng ko ? hay mình cần viết code để handle ? #todo 


### Vấn đề : đồng bộ trạng thái (success/fail) giữa s3 và service 

- dùng s3 notify + job đối soát #todo job đối soát cái gì? đối soát thông tin gì?
- ko để client upload xong sau tự báo done -> ko nên tin tưởng bất cứ cái gì từ client 



### Vấn đề : What if the upload fails at 99%?

- Some teams store this in localStorage so uploads can resume even after app restarts 
- clean up file ko được upload thành công sau 1-2 ngày cho đỡ rác. Chắc quét buổi tối xem file nào có ở s3 mà ko có trạng thái thành công ở db chính 

### Vấn đề : hacker lạm dụng s3 của mình để lưu trữ linh tinh 

#todo 
chạy scan lại 
### Vấn đề : đồng bộ meta data và file 

Công thức gợi ý: uploads/{user_id}/{timestamp}/{uuid}. -> để lưu file -

- Giúp phân loại file theo user.
- Tránh trùng lặp file (Collision).

Quy tắc vàng: Không bao giờ cho phép Client tự đặt tên file (Key). -> vì client có thể sửa id để ghi đè file của người khác 

### Vấn đề : tải file 

- nhanh -> CND 
- tải file lớn -> file 5GB -> Sử dụng Header Range: bytes=....
- Tải tiếp (Resumable): Nếu mất mạng, trình duyệt chỉ cần yêu cầu các byte còn thiếu.
- Xem trước (Streaming): Bạn có thể xem đoạn giữa của một video mà không cần tải đoạn đầu.
- chỉ cần server sp support range requests 
- Presigned URLs are generated entirely in your application's memory using your cloud credentials - no network call to storage is needed.
- If you need to validate or inspect file contents as they upload (compliance, security scanning), you must proxy through your servers to see the data.