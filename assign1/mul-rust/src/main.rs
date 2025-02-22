use rand::Rng;
use std::time::Instant;
mod naive_approach;
mod optimized;

fn generate_matrix(n: usize) -> Vec<Vec<f64>> {
    let mut rng = rand::thread_rng();
    (0..n)
        .map(|_| (0..n).map(|_| rng.gen_range(-10.0..10.0)).collect())
        .collect()
}

fn generate_flat_matrix(n: usize) -> Vec<f64> {
    let mut rng = rand::thread_rng();
    (0..n * n).map(|_| rng.gen_range(-10.0..10.0)).collect()
}

fn measure_time<F>(label: &str, func: F)
where
    F: FnOnce() -> Option<Vec<f64>>,
{
    let start = Instant::now();
    let result = func();
    let duration = start.elapsed();

    if result.is_some() {
        println!("{} completed in: {:.4?}", label, duration);
    } else {
        println!("{} failed!", label);
    }
}

fn main() {
    let sizes = [600, 3000];

    for &size in &sizes {
        println!("\n=== Matrix Size: {}x{} ===", size, size);

        let a_flat = generate_flat_matrix(size);
        let b_flat = generate_flat_matrix(size);
        let a_mat = generate_matrix(size);
        let b_mat = generate_matrix(size);

        measure_time("on_mult_line (2D Vec)", || {
            naive_approach::on_mult_line(&a_mat, &b_mat).map(|m| m.concat())
        });

        measure_time("on_mult_line_flat", || {
            naive_approach::on_mult_line_flat(&a_flat, &b_flat)
        });

        measure_time("on_mult_line_flat_transposed_b", || {
            naive_approach::on_mult_line_flat_transposed_b(&a_flat, &b_flat)
        });

        measure_time("final_mul_line (Optimized)", || {
            optimized::final_mul_line(&a_flat, &b_flat)
        });

        measure_time("optimized (Optimized)", || {
            optimized::optimized(&a_flat, &b_flat)
        });
    }
}
