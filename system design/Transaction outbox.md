https://viblo.asia/p/distributed-transaction-transactional-outbox-pattern-maGK7r695j2
https://www.youtube.com/watch?v=01jVbPHr3hc


###  Vấn đề : lưu dữ liệu thành công trong database nhưng bắn event lỗi 

- Làm sao đảm bảo DB update và event publish là “atomic” 

1.  tại sao ko đợi update event thành công rồi mới lưu vào db 
	1. lỗi bắn event đã trả tiền thành công, nhưng lưu DB thất bại 
	2. ko có cách nào rollback event 
	3. State change PHẢI xảy ra trước, Event chỉ là sự THÔNG BÁO về state đã tồn tại

2. tại sao lưu thành công vào db, rồi retry cho đến khi bắn event thành công ? 
	1. Crash = mất event vĩnh viễn -> ko thông báo được cho người dùng là đã thanh toán thành công -> mất thông báo 
	2. sau khi crash thì nếu instance sống lại -> ko tự động publish tiếp 
	3. cần bảng outbox để lưu event cần bắn để đảm bảo ko bị mất dữ liệu event cần bắn + có thể retry 