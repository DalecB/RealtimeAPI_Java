-- 관리자 계정은 password_hash가 있는 users 행이다. POST /users로 만든 플레이어는 NULL이라 로그인할 수 없다.
-- projects.admin_id가 users(id)를 참조하므로 별도 테이블 대신 컬럼으로 구분한다.
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
