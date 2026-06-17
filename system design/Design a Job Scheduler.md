
### vấn đề : thiết kế bảng để chứa được cả chạy theo giờ (mỗi 10h) và chạy vào một thời điểm 

- tách bảng - bảng template, và bảng instance - bảng instance chứa mỗi lần chạy cụ thể
- Giống việc tách bảng của notification template - và bảng từng thông báo cho từng user cụ thể
- chạy theo giờ -> ví dụ chạy mỗi 10h -> tạo trong bảng instance trước cho 100 ngày tiếp theo 
- chạy vào một thời điểm -> tạo trong bảng instance trước cho bản ghi đó, job id đó 


### vấn đề : query 1h tiếp có những job nào cần chạy -> partition database theo time bucket 

- partition database theo time bucket 

### vấn đề : user muốn xem lịch sử job của họ 

- phải query theo user, nhưng hiện tại đang partition theo time bucket 
- thêm GSI index trong cassandra là được  

### vấn đề : làm sao thực hiện job trong 2s sau khi setup 

- query 1s 1 lần -> chết db
- cách 2 : a two-layered scheduler architecture (phase 1 là cách 5 phút query db 1 lần + đẩy vào queue sort theo execution time) + phase 2 poll từ queue và thực hiện job  #todo 