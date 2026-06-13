
https://www.hellointerview.com/practice/system-design/cmm5nokkf04qu09ad0eu59mgb?q=cmm5rwyx21i1a07ad50dkaduj

# Requirements

- Non-functional - Fault tolerance : data lost là ko thể chấp nhận vì liên quan đến đếm số lượt click quảng cáo cho đối tác tính tiền -> write-ahead logs, data replication, or message queues 
- Core Entities là Clicks và Ads 
- **Input**: Ad click data from users
- **Output**: Ad click metrics for advertisers"
- Data flow : 
	1. User click advertise
	2. System save the click 
	3. Advertisers query the click metrics
	
# High level design 

## 1) Users can click on ads and be redirected to the target

#### Client side redirect 

- có sẵn link ở advertise, click vào thì redirect đồng thời call api /click để đếm lượt click quảng cáo. 
- Nhược là người ta có thể copy trực tiếp link và vào quảng cáo mà mình ko đếm được thông qua api /click 

#### Server side redirect 

- khi click vào ad thì mới call api /click sau đó trả về http code là 302 (redirect)

## 2) Advertisers can query ad click metrics over time at 1 minute intervals

#### cách 1 : Tồi - lưu click events và query trong cùng 1 db 

- nếu mà cần lên production chắc mình nghĩ giải pháp này đầu tiên
- ví dụ muốn thống kê số lượng click của các user trong một khoảng thời gian 

| EventId | Ad Id | User Id | Timestamp  |
| ------- | ----- | ------- | ---------- |
| 1       | 123   | 456     | 1640000000 |

```sql
SELECT user_id, COUNT(*)
FROM ClickEvents
WHERE time BETWEEN 'xxx' AND 'yyy'
GROUP BY user_id; (note group by = gom các nhóm theo cột này, và tính toán trên từng nhóm) 
```

- sql database nhanh chóng thành bottleneck (điểm nghẽn) với 10k click per second, và lệnh group by thì chậm và ko hiệu quả 

##### chính xác là với bao nhiêu click per second thì database là điểm nghẽn ? 

- với 10k click/second = 3600 * 10k = 36M row click trong 1 hour. And each click about 100Byte 3.6 GB each hour. Mà lệnh group by = quyét hết để hash hoặc sort để gom nhóm (index chỉ giúp phần where) + I/O để đọc hết từ 1 cột  
- write-heavy (36M record mỗi giờ) + read-heavy (group by trên hàng triệu dữ liệu chắc chắn chậm triệu dữ liệu chắc chắn chậm)  
- cải tiến bằng cách tách read path và write path để scale riêng + đừng có group by mà phải quét hết 1 cột để group by, mà columnar storage của click house  
  
##### Tại sao postgree, mysql throughput của ghi chỉ vài k write per second là bottleneck dù có tăng RAM, CPU, DISK, thế cassandra ghi cả trăm k dữ liệu thì sao ??  

-  bottleneck của postgree, mysql chính là I/O bền vững (durable writes) để đảm bảo ACID - hình như là random write. Còn cassandra thì dùng LSM (log structured merged tree) nên là sequential write I/O nên nhanh.  
  
##### Nếu partition db thì có đáp ứng được ko?

- đáp ứng được write tăng thêm, nhưng khi tổng hợp dữ liệu vẫn ko ổn, vì phải scan hết - ví dụ group by theo tháng.  
- cải tiến bằng pre-aggregate của click/minute thay vì tổng hợp trên raw event  

##### random write vs sequential write 

- một cái phải tìm đúng vị trí trên disk để ghi (vị trí random trên disk), một cái append liên tục thì chả nhanh hơn  
- fsync là thao tác ghi dữ liệu từ WAL (write ahead log - append log) xuống DISK (có WAL buffer ở RAM và WAL file là disk cache của OS)

##### tại sao lại có fsync trong db, và size batching nên là bao nhiêu ? 

- và nếu gom đủ 1000 dòng mà lưu xuống disk bị lỗi thì có bị mất hết ko ?-> có mất hết vì rollback, là trade off giữa throughput và durability vì batch nhỏ thì đảm bảo ko mất dữ liệu nhưng giảm throughput khi crash  
- PostgreSQL mặc định fsync mỗi transaction khi commit để đảm bảo durability. (có mode khác là group commit)  
- Partitioning = chia nhỏ dữ liệu trong một node. Sharding = chia nhỏ dữ liệu ra nhiều node.

### cách 2 : giải pháp tốt, tách database phân tích với batch processing 

- click -> lưu raw event trong db bình thường
- xử lý theo batch sang database phân tich -> lưu dữ liệu được tính toán trước cho query 
- để xử lý ghi nhiều -> dùng cassandra. LSM tree. Đầu tiên là ghi vào append-only commit log để đảm bảo ko mất dữ liệu (tức lưu log thôi hả - ví dụ câu query ? câu insert ?)
- sau đó ghi vào memory sorted structure (memtable). Memtable định kỳ lưu xuống đĩa dưới dạng sstables > sstable thì tối ưu cho đọc 1 rows cụ thể, nhưng range query và query tổng hợp thì chậm -> batch processing để tổng hợp trước  
- 10k click per second, mỗi click tầm 100 byte, 5p chạy batch một lần -> 10K * 100 Byte * 60 * 5 = 300MB đủ để chạy trên một instance ko cần apache spark, nhưng khi data grow lên thì dùng spark tốt hơn + có thể nói về map-reduce trong interview. Spark sẽ đọc raw event song song và tổng hợp vào db phân tích (analysis database) 

##### Database phân tích 

- Online Analytical Processing (OLAP) databases như Redshift, Snowflake, or BigQuery. 
- dùng định dạng lưu trữ cột (columnar storage formats) -> tối ưu cho query tổng hợp (COUNT, SUM, AVG) cực nhanh so với row-based database và xử lý dữ liệu lớn 

##### Tại sao ko dùng database chuỗi thời gian ( time series database ) như  InfluxDB or TimescaleDB  

-  vì nó phù hợp khi muốn biết metric X qua thời gian Y (theo thời gian) 
- Còn mình là hàng triệu ad, người quảng cáo thì muốn chia dữ liệu theo nhiều chiều (loại thiết bị, địa lý, chiến dịch,..) -> db phân tích handle truy vấn nhiều chiều, nhiều khoảng giá trị (high-cardinality, multi-dimensional querying)

##### Nó ko là giải pháp tuyệt vời 

- xử lý traffic tăng đột biến khó khăn hơn -> nhiều write hơn -> next batch sẽ phải xử lý nhiều hơn, có khi lâu hơn 5p (interval của batch) 
- delay cộng dồn khi người quảng cáo lại muốn xem nó nhất (big ad)
- nên dùng stream (ống nước, suối) - xử lý liên tục

##### Max write của cassandra là bao nhiêu ?  
- benchmark thực tế khoảng 50k-100k write persecond. Còn đọc 1 row cụ thể thì khoảng 10-50k per second.  
- thế range query thì bao nhiêu ? -> khoảng vài nghìn (https://benchant.com/ranking/database-ranking)  
  
##### Tại sao sstable thì tối ưu cho đọc 1 rows cụ thể, nhưng range query và query tổng hợp thì chậm  

- vì truy vấn 1 row thì dùng bloom filter + index được, nhưng range query chậm vì dữ liệu phân tán ở nhiều SSTable (sorted string table)  
  
##### LSM-Tree (Log-Structured Merge-Tree)  

- ghi append only trước, ví dụ có nhiều thao tác update thì ghi append log trước, ko tìm chính xác row cần update để update  
- do đó đọc chậm (vì dữ liệu chưa update, cần job để tổng hợp dữ liệu)  
- 40k write per second và có thể tăng tuyến tính nếu thêm node lên hàng triệu - xem thêm về cơ chế LSM trees  
  
##### Row-based (MySQL)

- Nhảy đến đúng vị trí của User đó trên đĩa, bốc 1 phát là lấy được tất cả các cột (vì chúng nằm sát nhau). Cực nhanh khi cần lấy các thông tin liên quan của 1 user  
  
##### Columnar (ClickHouse)

- Phải mở file của cột Tên, file của cột Email, file của cột Địa chỉ... tổng cộng mở hàng chục file khác nhau chỉ để lấy thông tin của 1 người.  
-  thử xem Big query, SnowFlaketại sao ? Khi query SELECT Age FROM Users, engine vẫn phải đọc toàn bộ row từ disk (Name, Email, Address…) lên RAM rồi mới lấy Age.  
- vì dữ liệu disk i/o hoạt động theo block/page (4kb hoặc 8b), mỗi block chứa nhiều row, mỗi row chứa nhiều cột của hàng đó  
  
##### Tại sao dữ liệu disk i/o hoạt động theo block/page? 

- vì để tối ưu đọc, ko phải đọc từng byte + ảnh xạ page trên RAM với block trên disk để quản lý bộ nhớ (OS và disk có chuẩn chung để trao đổi dữ liệu). 
- Block là đơn vị quản lý lưu trữ của disk. Page là đơn vị quản lý bộ nhớ của OS.

### cách 3 : giải pháp tuyệt vời 

##### Dùng bộ xử lý stream như Flink hoặc Spark streaming để đọc event từ kafka và xử lý real time 

- tại sao ko tự xây consumer group xử lý từ kafka, chỉ cần đếm số lượt event ad trong Redis là đủ mà nhỉ ? Và Flink cũng đếm trong memory thôi. Đếm đủ 10 event thì lưu vào db phân tích chẳng hạn 

##### xử lý aggregation theo window + event time

- watermarks để biết khi nào đóng window -> cho phép late event tối đa, cho phép event đến trễ 5s vẫn count trong phút đó 
- đảm bảo exactly-once -> đảm bảo kiểu gì ? 
- fault tolerance + recover state -> thì consumer chết thì khi sống lại nó cũng đọc lại offset mà, có bị gì đâu ? 
- dùng event time (khi k/hang click event) thay vì thời điểm Flink nhận event để đảm bảo tổng hợp chính xác kể cả event đến ko đúng thứ tự (out of order)  
- nếu run spark job 1p 1 lần vs 1 phút boundaries của Flink thì có vẻ cũng ko khác nhau lắm về latency nhỉ ? 
- sẽ tiện hơn là giảm window time xuống 5s một lần của Flink thay vì 5s chạy batch 1 lần của Spark 

##### Flink  

- Thay vì lưu từng raw click, nó sẽ cộng dồn theo từng phút.  
- Ví dụ: Trong 1 phút, Ad 123 có 500 clicks. Bạn chỉ lưu đúng 1 dòng vào DB: AdId: 123, Minute: 08:00, Clicks: 500.  
- ưu là nó cộng dồn sẵn cho mình, mình có thể xử lý bằng code cũng đc + xử lý phục hồi cho mình, vì flink ko phải là stateless mà là statefull vì nó giữ dữ liệu để đếm.   
- tại sao ko để trực tiếp vào Flink luôn mà lại phải đi qua kafka nữa ??  
- vì kafka là durable log, Flink down thì vẫn còn kafka + 1 event có thể dùng cho nhiều nghiệp vụ khác nữa -> ví dụ fraud detection, ML, billing

# Deep dive

## 1. How would you scale the system to handle 100k clicks per second without dropping any?

- postgree ko thể handle 100k write per second, bởi vì limit của sql db là khoảng 200-2k write per second  
- có thể ghi vào redis cho nhanh , nhưng tốn bộ nhớ (ko khả thi) + inconsistency -> mất dữ liệu  
- mongodb, cassandra khoảng 10k-50k write per second -> có thể partition ghi theo ad id thì vẫn ok? trade off là muốn query tổng hợp theo user id -> chậm hơn bình thường  
- hoặc ghi vào kafka, và để đảm bảo ko bị mất message, thì chỉ khi ghi vào kafka thành công thì mới trả về 200 status code (tính là một lượt click thành công), 4xx, 5xx nếu ghi vào kafka lỗi để người dùng thử click lại vào quảng cáo :))  
- **click processor service** : scale bằng cách cho thêm nhiều instance thôi, vì service là stateless mà :v, chỉ cần cái load balancer trước là được  
- **Stream** : scale bằng cách thêm partition cho kafka, có thể partition theo adId hoặc userId đều ok.  
- **Stream processor** : Flink có thể add thêm job instance là ok, xử lý job theo adId  
- **database phân tích** : snowflake hoặc bigquery thì xử lý scale tự động, bạn ko quản lý chỗ đặt data, còn với giải pháp tự quản lý như clickhouse thì có thể shard bằng advertiserId vì dự đoán là người quảng cáo sẽ xem tất cả các ad của họ trong 1 view  
  
#### hot shards 

- chia nhỏ hot shard ra thôi :)), bằng cách thêm số ngẫu nhiên kiểu : AdId : 1-N, và có thể dự đoán hot shard bằng số tiền khách hàng chi tiêu vào AdId đó. Và trước khi ghi vào database, thao tác SUM từ nhiều partiton thì có thể xóa suffix để dùng AdId là key trước khi lưu vào database

#### các cách scale cho kafka?  

- partition của kafka nếu nhiều thì có thể chia ra nhiều instance khác nhau được ko ?  
- Partition theo adId có ok ko ? Có cần tất cả các event của cùng một adId theo thứ tự ko ? có vẻ ko cần  
- ko thể partition theo userId được, vì user Id rất nhiều -> được vì hash rồi mod vào partition tương ứng mà  
- thế nên để mỗi job Flink đọc 1 partiton hay để đọc cái nào cũng được ? Nếu 1 Flink job đọc 1 partition thì phải partiton kafka theo adId vì Flink cần tổng hợp dữ liệu theo adId 
- ko ở phần hot shard thì có bảo tổng hợp SUM từ nhiều partition ? tức 1 Flink job đọc từ nhiều partition ?

## 2) How can we ensure that we don't lose any click data?

#### Kafka

- Ko sao vì kafka có replicates dữ liệu sang nhiều broker trong cùng 1 cluster nên kafka down thì vẫn còn dữ liệu  
+ kafka có thể lưu dữ liệu, mình có thể config tối đa 7 ngày retension chẳng hạn, thì khi stream processor down thì khi sống lại có thể đọc từ offset mới nhất mà ko lo bị mất dữ liệu

#### Flink 

- có chức năng checkpointing = định kỳ ghi dữ liệu vào S3, nếu đang tổng hợp batch mà down thì đọc từ checkpoint cuối và tiếp tục -> hữu ích khi aggregation windows lớn, 1 ngày hoặc 1 tuần  
- nhưng với case của mình, aggregation windows nhỏ, có thể mất 1 phút nhưng chỉ là dữ liệu tổng hợp, có thể đọc lại từ kafka và tiếp tục ?  

#### Đối soát dữ liệu (Reconciliation) 

- mất dữ liệu click = mất tiền, đảm bảo chính xác tuyệt đối + độ trễ thấp thường mâu thuẫn, code lỗi, event sai thứ tự -> job đối soát định kỳ mỗi ngày  
- dump raw event qua Kafka Connect S3 Sink Connector (native support)  
- dùng spark để đọc từ s3 và tổng hợp lại từ đầu, và so sánh với dữ liệu từ DB phân tích (từ stream). nếu ko khớp thì điều tra sai lệch, cập nhật dữ liệu đúng vào db (eventually consistency ? - tức chấp nhận real time có thể sai một tý, nhưng cuối ngày sẽ đúng)  = lambda architecture (stream xử lý real time cho độ trễ thấp, batch xử lý định kỳ cho độ chính xác (sourth of truth) )

#### tại sao ko xét click processor down, API gateway hoặc LoadBalancer down nhỉ ? 

- mặc định replicate dữ liệu sang nhiều broker là bao nhiêu phút 1 lần, và mặc định là sang bao nhiêu broker 

#### nếu kafka down thì sao ? 

- kafka có thể down được ko ? Nếu kafka down thì mất sạch click đến 
- nên để tổng hợp xong 1 phút aggregate rồi mới commit, hay commit liên tục? Và nếu đang tổng hợp dở 1 phút mà click processor down thì làm thế nào 
- lambda architecture này giống lambda ở AWS ko? và tại sao lại gọi là lambda architecture 

## 3) How can we prevent abuse from users clicking on ads multiple times?

#### giải pháp tồi 

- yêu cầu user loggin, rồi trích xuất user Id từ JWT và dùng userId và adId để deduplicate -> phải deduplicate trước khi vào Flink count -> có thể trước hoặc sau khi vào kafka 
- tồi vì yêu cầu user phải loggin + nếu mục đích là show 1 quảng cáo cho 1 user nhiều lần thì phải dedup dựa trên 1 lần hiển thị quảng cáo (ad instance) 

#### giải pháp tốt 

- check duplicate bằng impression ID trước khi vào Flink tổng hợp count, đẩy vào kafka rồi lưu trong redis
- nhưng hacker có thể gửi một đống impression ID giả mạo -> mình vẫn count -> mình có thể check impression ID từ FE với impression ID trong lưu trong db hoăc nhanh hơn là trước khi gửi adID và impression ID cho FE thì dùng signature = HMAC(secret, message) để hash, sau đó khi FE gửi xuống thì check redis để dedup + HMAC để đảm bảo gói tin ko bị sửa đổi 
- cache size + cache down thì sao. 100M click per day = 16bytes (128 bit) * 100M = 1.6 GB -> nhỏ 
- redis cluster để đảm bảo scale, cache down thì có cache replica + Redis persistence (RDB hoặc AOF) 

#### Như thế nào tính là duplicate click ? 

- 1 user click 1 ad trong 1 phút tính là duplicate, hay là click per AdId ?  
- tại sao user ID trích xuất từ JWT lại an toàn  
- cơ chế HMAC là gì ? nếu bị lộ shared key thì bị gì ko ? nó là shared key hay secret key ?  
- là secret shared key :v, lộ thì hacker đổi message như cơm bữa, vì so sánh của hash (message, key)  -> trong JWT thì secret key chỉ được BE cầm  
- impression ID là Id đại diện cho 1 lần quảng cáo được hiển thị cho user  
- nếu lưu vào kafka thành công, nhưng lưu trong redis thất bại thì sao ?  
- 1.6Gb nhưng đó là của 1 ngày, thì chỉ cần deduplicate của 1 ngày thôi à ? thế cần lưu tất cả thì sao ?? ví dụ deduplicate trong 1 tháng ??  
- thêm redis persistence thì tăng latency thì sao ??

## 4. How can you ensure low latency responses for advertiser queries, especially for aggregations over large time ranges?

- composite index + tính toán trước theo ngày, theo tháng, theo giờ, last week, last month, + chỉ show có thể xem theo ngày, giờ (ko cho query theo khoảng bất kỳ chẳng hạn)
- tạo mới table lưu lưu dữ liệu đã tính toán trước của daily, weekly qua cronjob. (giống cache, trade off giữa space và tốc độ query for most common query) 



> [!20 Interview questions]
> 1. Ghi chú thông thường