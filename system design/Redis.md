
## Tại sao cần redis cluster 

- High availability : mỗi node master có replica -> master sập thì có replica lên thay 
- Thêm node mới dễ dàng
- Tự chia 16.384 slots cho các node master, khi thêm node mới -> tự cắt slot cho node mới
	- so sánh với consistent hashing ?
	- tại sao là 16383 mà ko phải số khác ?