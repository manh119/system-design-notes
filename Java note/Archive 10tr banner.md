- hằng ngày cần xóa toàn bộ bảng user_banners sang bảng user_banners_arhive 
- Các lệnh đọc chỉ cần đọc ở user_banners (nhẹ)
- test xóa ko order by theo id và order by theo id, test vaccum 


## test dùng limit ko order theo id : 10tr dòng, 2m37s 

![[Pasted image 20260711004341.png]]


## test dùng order theo id : 10tr dòng, 2m52s

![[Pasted image 20260711005553.png]]




```sql 
`CREATE TABLE user_banners (
                              id BIGSERIAL PRIMARY KEY,
                              contract_number VARCHAR(50) NOT NULL,
                              identity VARCHAR(20) NOT NULL,
                              phone VARCHAR(20) NOT NULL,
                              key_banner VARCHAR(100) NOT NULL,
                              start_time TIMESTAMPTZ NOT NULL,
                              end_time TIMESTAMPTZ NOT NULL,
                              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


INSERT INTO user_banners (
    contract_number,
    identity,
    phone,
    key_banner,
    start_time,
    end_time
)
SELECT
    'CN' || LPAD(gs::text, 10, '0'),
    LPAD((100000000000 + gs)::text, 12, '0'),
    '09' || LPAD((gs % 100000000)::text, 8, '0'),
    'banner_' || (gs % 100),
    NOW() - ((gs % 365) * INTERVAL '1 day'),
    NOW() + (((gs % 365) + 1) * INTERVAL '1 day')
FROM generate_series(1, 10000000) AS gs;



CREATE TABLE user_banners_archive (
                                      LIKE user_banners INCLUDING ALL
);`
```