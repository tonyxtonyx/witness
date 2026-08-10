CREATE TABLE customers (customer_id BIGINT PRIMARY KEY, name VARCHAR(100), country VARCHAR(2));
CREATE TABLE products (product_id BIGINT PRIMARY KEY, name VARCHAR(100), category VARCHAR(50));
CREATE TABLE orders (order_id BIGINT PRIMARY KEY, customer_id BIGINT REFERENCES customers(customer_id), product_id BIGINT REFERENCES products(product_id), created_at TIMESTAMP, amount DECIMAL(18,2), status VARCHAR(30));
CREATE TABLE ai_experiments (experiment_id BIGINT PRIMARY KEY, model_name VARCHAR(100), created_at TIMESTAMP, quality_score DECIMAL(5,4), status VARCHAR(30));
INSERT INTO customers VALUES (1,'Ada Market','GB'),(2,'Nord Shop','SE'),(3,'Sakura Store','JP');
INSERT INTO products VALUES (10,'Analytics Starter','software'),(11,'Data Canvas','software'),(12,'Model Handbook','books');
INSERT INTO orders VALUES
 (1001,1,10,'2026-07-01 10:00:00',129.00,'paid'),
 (1002,1,12,'2026-07-02 11:30:00',39.00,'completed'),
 (1003,2,11,'2026-07-03 09:00:00',249.00,'paid'),
 (1004,3,12,'2026-07-04 15:45:00',39.00,'pending');
INSERT INTO ai_experiments VALUES
 (2001,'recommendation-v3','2026-07-01 09:00:00',0.9120,'completed'),
 (2002,'forecast-v2','2026-07-03 14:00:00',0.8740,'completed'),
 (2003,'ranking-v5','2026-07-05 16:30:00',0.9310,'running');
