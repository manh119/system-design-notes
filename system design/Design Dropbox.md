
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
- #todo nhưng mà phần chia file lớn thành file nhỏ và tự động upload lại khi upload lỗi 99% là tự có của http hay client phải tự triển khai nhỉ ?

## Uploading large files can also be challenging due to network interruptions. How does your design allow users to resume an interrupted upload without starting over from scratch?

- We can have local stoge to save progress of uploading
- When upload a large file, we devide large file into smaller chunk, each have an id and status in local storage
- Each time we upload successfully a chunk, we mark status success in local storage
- If we upload fail at 99% due to network, we can always be resumable
- Nếu mà fail do user tắt trình duyệt, do tắt app, tắt máy tính thì chịu chết :)), youtube cũng méo xử lý đc :)) 

## How can we reduce bandwidth usage and make the sync process faster than downloading full files each time they change?



## How can you ensure file security?

1. Dùng HTTPS để encrypt data trên đường truyền, tránh man in the middle 
2. Mã hóa file khi lưu ở S3, 
3. Access control : khi một user download một file, thì luôn check xem user đó nằm trong ShareList table ko của file đó ko.  #todo 