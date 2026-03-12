insert into metadata_objects (id, metadata_json)
values
('00000000-0000-0000-0000-000000000001', '{"owner":"ops","priority":"high","region":"us-east"}')
on conflict (id) do nothing;

insert into hardware_records (id, device_name, manufacturer, cpu, ram, storage, metadata_json)
values
('11111111-1111-1111-1111-111111111111', 'embabel-laptop-pro', 'Lenovo', 'Intel i7', '32GB', '1TB SSD', '{"os":"Windows 11","fleet":"engineering"}'),
('22222222-2222-2222-2222-222222222222', 'embabel-workstation', 'Dell', 'AMD Ryzen 9', '64GB', '2TB NVMe', '{"os":"Ubuntu 24.04","fleet":"ml"}')
on conflict (id) do nothing;

insert into contacts (id, name, email)
values
('33333333-3333-3333-3333-333333333333', 'Sam', 'sam@example.local'),
('44444444-4444-4444-4444-444444444444', 'Joe', 'joe@example.local'),
('55555555-5555-5555-5555-555555555555', 'Alex', 'alex@example.local'),
('66666666-6666-6666-6666-666666666666', 'Samuel Owusu', 'samuel.owusu@example.local')
on conflict (email) do nothing;
