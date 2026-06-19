

## Session 

- access_token được lưu trong redis, được sinh ra khi user login 
- Mỗi request đều gửi token để check TTL + token gửi xuống và token trong redis bằng nhau hay ko 
- Khi revoke (logout) chỉ cần xóa token trong redis 

## JWT 

- Có chứa role, username, expire at trong chính nó 
- Vì nó đã được ký -> nên đảm bảo ko bị thay đổi nội dung #todo chắc chưa ?
- Check đúng role, lấy username từ đó, server ko revoke bất kỳ lúc nào được vì nó là stateless

## Keycloak 

- #todo thực hành 
- đơn giản là phân role 