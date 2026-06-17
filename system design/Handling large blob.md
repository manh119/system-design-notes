
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