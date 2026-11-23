package com.bookstore.order.config;

import com.bookstore.order.model.Book;
import com.bookstore.order.repository.BookRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** Thêm vài cuốn sách mẫu khi CSDL còn trống, để chạy thử ngay. Xóa/sửa trong trang quản trị. */
@Component
public class DataInitializer implements CommandLineRunner {

    private final BookRepository bookRepository;

    public DataInitializer(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Override
    public void run(String... args) {
        if (bookRepository.count() > 0) return;

        bookRepository.saveAll(List.of(
            new Book("Đắc Nhân Tâm", "Dale Carnegie", "Kỹ năng sống", 86000, 50, null,
                    "Cuốn sách kinh điển về nghệ thuật giao tiếp và ứng xử."),
            new Book("Nhà Giả Kim", "Paulo Coelho", "Văn học", 79000, 40, null,
                    "Hành trình theo đuổi ước mơ của chàng chăn cừu Santiago."),
            new Book("Tuổi Trẻ Đáng Giá Bao Nhiêu", "Rosie Nguyễn", "Kỹ năng sống", 90000, 30, null,
                    "Những chia sẻ về học tập, làm việc và trải nghiệm tuổi trẻ."),
            new Book("Cho Tôi Xin Một Vé Đi Tuổi Thơ", "Nguyễn Nhật Ánh", "Văn học", 85000, 25, null,
                    "Câu chuyện hồn nhiên, hài hước về tuổi thơ."),
            new Book("Kinh Tế Học Vĩ Mô", "N. Gregory Mankiw", "Kinh tế", 195000, 10, null,
                    "Giáo trình kinh tế vĩ mô được sử dụng rộng rãi."),
            new Book("Sapiens: Lược Sử Loài Người", "Yuval Noah Harari", "Khoa học", 229000, 0, null,
                    "Lịch sử loài người từ thời đồ đá đến hiện đại.")
        ));
    }
}
