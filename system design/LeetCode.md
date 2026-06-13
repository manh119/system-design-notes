# High level design  
## 1. Người dùng có thể submit code và nhận feedback ngay lập tức  

#### bad solution : chạy code ở API server 
- ko tốt vì hacker có thể submit code phá hoại + code crash, out of memory -> crash server chính  
  
#### good solution : chạy ở VM machine, khởi động chậm + tốn resource (costly)  
  
#### greate solution : chạy ở container của docker (lightweight)  
+ VM machine thì tách bạch OS, application, thư viện  
+ container thì chung OS, nhưng có thể tách biệt RAM, CPU, file system -> cẩn thận về security vì chung host kernel + chia sẵn tài nguyên vì sợ người dùng lợi dụng tài nguyên tính toán


#### các container thì chung với nhau gì và riêng với nhau gì ?  
- chắc là chung kernel, riêng RAM, CPU, file system.  
  
#### Nếu chạy bằng container, thì API server lấy kết quả chạy testcase kiểu gì?  
+ bước 1 chắc là copy code vào container  
+ bước 2 là chạy container tương ứng với ngôn ngữ đó  
+ API server lấy kết quả qua mount file (volumn) hay có API nào nhỉ? hay stdout nhỉ ?  
- container - chỉ là đóng gói code + dependency, thư viện

## Người dùng có thể thấy live leaderboard  
- 100K user, 10 problem, contest 120p  
- cách đơn giản cứ 5s một lần thì FE query top k leader boad bằng group by + sắp xếp theo điểm


# Deep dive :  

## Đảm bảo isolation and security when running user code  
- set up read only file system, nếu write result thì write vào tmp dictionary và xóa sau đó. Limit CPU, RAM, Timeout, nếu quá thì kill. Và chặn network call và system call.
## Cách để leader boad hiệu quả  
#### cách tồi : query db để sắp xếp mỗi 5s  

#### cách tốt hơn  
- query 30s db một lần để lấy leaderboard, và lưu vào redis, tất cả client đều query vào redis, và chấp nhận real time ở 30s  
- 
#### cách tuyệt vời, khi chấm điểm xong thì lưu user + score ở cả db và sorted set redis. 
- Khi query leader board thì query trong redis thôi. Mọi cập nhật khi submit update vào db thì cần update score trong redis tương ứng + để client query 5s một lần là đủ chấp nhận real time (thực tế youtube khi upload video thì cũng chạy 1s một lần để check status, và nhìn API xem, có đúng REST API đéo đâu :)), /upload/feedback kìa :)) - ko phải cái nào cần real time thì cũng SSE và websocket đâu má
- 
## Sp 100k user trong contest  
- 10k submit 1 lần -> 1 test case chạy trong 100ms + cần chạy 100 test case -> cần 10s cho 1 submit chạy xong và đây là CPU bound  
- thì scale, tăng thêm instance của container mà chạy thôi :)), dệ. + dựa vào số lượt đăng ký cho contest thì đoán được. + có thể add queue vào giữa worker và container để đảm bảo ko submit nào bị mất + nếu ví dụ container bị die, crash thì có thể retry chẳng hạn (điều tự nhiên của queue)


