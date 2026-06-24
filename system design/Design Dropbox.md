
# Funtional requirements

1. Users can upload files to remote storage
2. Users can download files from remote storage
3. Users can automatically sync files across devices
4. Users should be able to share a file with other users and view the files shared with them

Out of scope: 
1. Edit file 
2. View file without download 

# Non Funtional requirements

1. Scalable to support 100M daily active users, and size file up to 50 GB 
2. High availability + eventually consistency 
3. fault tolerant, resumable uploads
4. Low latency for sync file beetween devices, within 1 minute 

Below the line : 
1. Scan virus
2. Versionning file 
3. Storage limit for each user 

# Entity

1. User
2. FileInfo
3. Device

# API design 

1. Users can upload files to remote storage
	- POST api/v1/file/upload -> list of File id
	- {
		- List of File
	- }
	
2. Users can download files from remote storage
	- GET api/v1/file/download?fileId=123 -> File

3. Users can automatically sync files across devices
	- GET api/v1/files?changed_since -> list of ChangeEvent  

# High level design 

## How will users be able to upload files?

![[Pasted image 20260621161548.png]]

FLow would be : 
1. User get presign URL from File service
2. File service create new record on FileInfo table with status = UPLOADING and return presign URL for user
3. User upload file directly in s3 by using presign URL
4. S3 notify file upload successfully, so File service update status = UPLOADED in FileInfo table

## How will users be able to download files from remote storage?

![[Pasted image 20260621162059.png]]

The flow would be : 
1. User choose a file to download
2. File service check authen of that user, the owner of file Id and generate a presign URL for download
3. User download file from s3 directly using presign URL

## Users should be able to share a file with other users

- Vì file có thể shared với 1 user với các quyền khác nhau, chỉ đọc, cả đọc và ghi, thời gian share (một file có thể share với nhiều user, một user có thể được share bởi nhiều file nên đây là quan hệ N-M, nên cần tách bảng)
- Cần phải có bảng SharedTable (id, FileId, userId, role, ....)
- Chứ nếu lưu một list các user được share trong FileInfo thì lúc thêm user, lúc xóa user, rồi các dữ liệu như quyền đọc, ghi, thời gian share không biết để vào đâu. 
- Và query list tất cả các file được share của 1 user cũng khó, vì phải vào từng file info để check user đó nó nằm trong list ko. 
- Còn nếu giữ sharedUser ở FileInfo, và sharedFiles ở User table, thì 2 mảng này cần phải đồng bộ với nhau khi update. 

## Design how the Dropbox desktop / mobile sync agent detects edits in the user's local Dropbox folder and uploads those changes to remote storage.

Bài toán ở đây là : File có ở cả local device và cloud, chứ không phải là File chỉ có ở cloud :v 

Flow would be : 
1. Dropbox desktop use tool monitor file change, directory change of operating system to check local edits change, if have an edits it call an api to publish changeEvents 
2. File service use this changeEvents to modify accordingly FileInfo table like status, updated_at, name, ....

## Design how the sync agent on a device discovers changes that happened in the cloud and applies them to the local file system.

FLow would be : 
1. Each time we open dropbox desktop, we set up a websocket connection 
2. When remote files change, remote server push a changeEvents through the websocket connection 
3. Dropbox desktop will update local file correctly

Combine with polling each 2 minute in case of dead websocket connection GET /files/changes?since={timestamp} to get latest change, remote server check if anyfile of that user have updated_at newer than timestamp. 

Nhưng nếu dùng websocket thì làm sao remote server biết có file nào của user nào change nhỉ ? và changeEvents nên được thiết kế như thế nào ? 
- Dùng redis pub/sub, và khi có ai đó gọi API POST để thực hiện một changeEvents ở FileInfo table, khi update ở FileInFor table xong thì cũng publish một event vào redis pub/sub để gửi changeEvents qua websocket connection
- #todo 

# Deep dive

## How will your system handle uploading large files (up to 50GB) given the limitations of most servers and clients on the size of a POST request body?

- We can use multi-part upload of http and upload each chunk of file. 
- Client should handle for resumable upload
- Như youtube là nếu đóng trình duyệt trong lúc đang tải video lên là cook 

UX quan trọng : 
- thanh tiến độ tải 
- Resumable upload 

 Resumable upload : 
- Client chia nhỏ thành chunk 5-10MB (tại sao là số này) và tính Fingerprint (hash-256) của file và các chunk 
- Client query file có Fingerprint này đã từng upload cho user này chưa -> backend trả về list các chunk đã xong 
- Nếu là file mới, Backend gọi API `CreateMultipartUpload` của AWS S3 để lấy một `uploadId` và tạo các Presigned URLs cho từng chunk, lưu trạng thái vào FileMetadata table và trả về cho Client.
- Upload xong chunk nào thì client gửi Etag để backend xác thực Etag qua API listParts  và update status chunk 
- Upload xong thì Backend gọi CompleteMultipartUpload của s3 cùng với một list Etags và partnumber để s3 ráp thành file hoàn chỉnh và cập nhật trạng thái thành uploaded 
- Nếu người dùng tắt trình duyệt, thì lúc upload lại thì client phải tự chia nhỏ gói tin lại đúng ko? và upload phần bị thiếu ?  
- Thực tế có sẵn : [JavaScript SDK](https://aws.amazon.com/blogs/compute/uploading-large-objects-to-amazon-s3-using-multipart-upload-and-transfer-acceleration/) which will handle all of the chunking and uploading for you, [Multipart Upload](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html) that allows you to upload large objects in parts. #todo demo 

## Uploading large files can also be challenging due to network interruptions. How does your design allow users to resume an interrupted upload without starting over from scratch?

- We can have local stoge to save progress of uploading
- When upload a large file, we devide large file into smaller chunk, each have an id and status in local storage
- Each time we upload successfully a chunk, we mark status success in local storage
- If we upload fail at 99% due to network, we can always be resumable
- Nếu mà fail do user tắt trình duyệt, do tắt app, tắt máy tính thì chịu chết :)), youtube cũng méo xử lý đc :)) 

## How can we reduce bandwidth usage and make the sync process faster than downloading full files each time they change?


## How can we make uploads, downloads, and syncing as fast as possible?

- Tối ưu download bằng CDN (cache file hay dùng)
- Tối ưu upload bằng gửi chunk song song + adaptive chunk size (mạng mạnh thì chunk to, mạng yếu thì chunk nhỏ)
- Tối ưu đồng bộ bằng kỹ thuật Content-Defined Chunking (cách của dropbox)
	- Thay ví cứ đúng 5MB là cắt chunk, thì dùng Rolling Hash
	- Nếu chèn thêm 1Byte vào đầu file lớn, thì chỉ có chunk đầu thay đổi, các chunk khác vẫn giữ nguyên nên việc đồng bộ dữ liệu sẽ nhanh hơn 
- Tối ưu bằng nén dữ liệu 
	- Nén và giải nén hoàn toàn ở client, Server ko nén, giải nén
	- Nén thông minh : File media, ảnh .png, video.mp4 đã được nén -> nén thêm cũng ko được mấy. File text, code, log -> nên nén 
	- Thuật toán nén : gzip, Zstandard của meta
	- Nếu hệ thống yêu cầu mã hóa dữ liệu -> nén trước, mã hóa sau 

## How can you ensure file security?

1. Mã hóa khi truyền file :Dùng HTTPS để encrypt data trên đường truyền, tránh man in the middle 
2. Mã hóa khi lưu trữ : file khi lưu ở S3 (có tính năng tự động của s3)
3. Kiểm soát quyền truy cập : khi một user download một file, thì luôn check xem user đó nằm trong ShareList table ko của file đó ko. 
4. Phát tán presign-URL cho người khác -> người khác vẫn download và upload được. -> giới hạn chỉ 5p để tải, và check địa chỉ IP, hoặc device ID đúng người request thì mới cho tải. Hoặc kiểm tra với Authentication Cookie của trình duyệt. 
	- Kết hợp với CDN : CDN dùng public key để giải mã chữ ký presign URL, check quá hạn hay đúng IP ko thì mới cho tải 