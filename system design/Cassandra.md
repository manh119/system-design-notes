(partitioned wide-column storage model) + eventually consistent  
  
# Data model  

- keyspace = database in postgree  
- table  
- row  
- column : mỗi cột có metadata timestamp, cho biết thời điểm dữ liệu được ghi - conflict -> last write wins  
  
# Primary key  

- khác với postgre thông thường, primary key dùng để not null + unique + index primary key trong cassandra còn dùng để :  
- partition key : partition theo key nào -> bắt buộc phải có (tức bắt buộc phải partition :)) )  
- cluster key : trong mỗi partition thì được sort theo trường nào  
  
# Key concept  

## Partitioning 

- (= chia dữ liệu) : cassandra dùng consistent hashing (hashing ổn định và đều nhất, khi một node ra hay vào cluster thì ít phân bổ lại data nhất = ít phải di chuyển dữ liệu giữa các partition)  
+ cách truyền thống là để tìm node cho một value là hash(value) % num_nodes. -> nhược điểm là khi một node mới vào hay ra thì sẽ cần di chuyển data nhiều giữa database -> ko tốt  
+ thay vì map 1 node cho một giá trị, thì map 1 node cho một RANGE  
+ để giải quyết quá tải cho một node (uneven hash) -> dùng virtual node, tức một node trên RING chỉ là một virtual node, và một hay nhiều virtual node gắn với một node vật lý.  

## Replication

-  = copy dữ liệu - đảm bảo high available  
+ quyét theo chiều kim đồng hồ, và lưu liên tiếp bản copy vào virtual node ko cùng node vật lý  

## Consistency

-  (= đọc dữ liệu mới nhất của write ko, mọi node đều có chung một dữ liệu ko, khi network partition xảy ra thì ưu tiên phản hồi ngay mặc dù dữ liệu cũ của node (availability), hay là đợi network để lấy được dữ liệu mới nhất (consistent)  
+ trade off = bao nhiêu replicas phải phản hồi thì read/write mới thành công  
+ quorum (n/2 + 1) để đảm bảo read luôn thấy write. đừng có kiểu write vào node 1,2,3. Nhưng read lại đọc 4,5,6 = là cách cần ít phản hồi nhất từ replica nhưng vẫn consistency

## Query Routing 

- (giải quyết single of failure + bottleneck khi scale của node master)  
+ các node đều biết node cluster + data ở node nào, replica nào thông qua gossip protocol.  
+ mỗi node đều ngang hàng, đều có thể nhận request  

## Storage Model 

- (tối ưu cho write)  
+ write path = ghi vào commit log ở disk (đảm bảo ko bị mất dữ liệu), ghi vào memtable ở RAM, định kỳ flush memtable ở RAM xuống SSTable (khi đủ size hoặc đủ time)  
+ read path = đọc từ memtable trước, nếu ko có thì đọc SSTable ở disk, dùng bloom filter để xem SSTable này có hay ko, rồi tự gom các state về state cuối cùng  

## Gossip

- (giải quyết trao đổi thông tin giữa các node, đảm bảo ko cần node master để điều phối)  
+ Generation + Version -> giúp biết được thông tin nào mới hơn  
  
## Fault Tolerance  

+ Hinted handoff : giữ write tạm  
+ phát hiện node fail bằng cách xem response time với lịch sử response time của nó + gossip heartbeat timing  
- Data model : thiết kế theo query pattern thay vì entity-relation pattern như SQL  
- snow flake id : id được sắp xếp theo thời gian

## Chính xác thì LSM tree và SSTable có hình dạng như nào ? 

## Generation + Version 

- ko phải là cứ nhận được thông tin nào sau thì nó là mới nhất à, tại sao lại phải có version?

## wide column storage model là gì?  
- số lượng các cột có thể rất lớn, mỗi row ko cần cùng cấu trúc với row khác  
giống  
- khác với dạng document của mongodb là kiểu lưu json object trong object  
  
## tại sao trong hash thông thường thì khi một node vào hay ra thì cần di chuyển data nhiều giữa các database ??  

- ví dụ ban đầu hash(value) % 4, nhưng sau khi thêm 1 node thì phải là hash(value) % 5 -> giá trị hash thay đổi, tất cả dữ liệu phải tái xác lập lại vị trí  

### tại sao map 1 node cho một RANGE lại ko cần di chuyển dữ liệu nhiều khi một node thêm hay bớt  

- ví dụ vòng tròn RING là -2^63 đến 2^63, thì chỉ cần hash 64 bit là ok. value 50->80 thuộc node C, khi thêm node B là giá trị 70, thì chỉ cần remap 50->70 vào node B, còn 70->80 vẫn là của node C  
  
### tại sao virtual node lại giải quyết được uneven hash ? 

- chia server thành nhiều mảnh hơn nữa  
- thế nếu một node vật lý gồm 256 virtual node bị out thì phải remap lại value hết à -> đúng, remap lại toàn bộ vnode  
  
### giải thích tại sao cassandra ko phải ACID database ?  
  
### giải thích ưu điểm/ nhược điểm của master slave architecture của postgree, và so sánh ưu nhược điểm với peer to peer architecture của cassandra