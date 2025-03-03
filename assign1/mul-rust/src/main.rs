use rand::Rng;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::time::{Duration, Instant};

mod naive_approach;
mod optimized;
mod parallel;

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

struct ResultRow {
    matrix_size: usize,
    method: String,
    duration_secs: Option<f64>,
}

// This function now returns the elapsed time as an Option<Duration>
fn measure_time<F>(label: &str, func: F) -> Option<Duration>
where
    F: FnOnce() -> Option<Vec<f64>>,
{
    let start = Instant::now();
    let result = func();
    let duration = start.elapsed();

    if result.is_some() {
        println!("{} completed in: {:.4?}", label, duration);
        Some(duration)
    } else {
        println!("{} failed!", label);
        None
    }
}

fn main() {
    let mut sizes: Vec<usize> = Vec::new();
    for i in (600..3001).step_by(400) {
        sizes.push(i);
    }

    // Store results here
    let mut results: Vec<ResultRow> = Vec::new();

    for &size in &sizes {
        println!("\n=== Matrix Size: {}x{} ===", size, size);

        let a_flat = generate_flat_matrix(size);
        let b_flat = generate_flat_matrix(size);
        let a_mat = generate_matrix(size);
        let b_mat = generate_matrix(size);

        if let Some(duration) = measure_time("on_mult_line (2D Vec)", || {
            naive_approach::on_mult_line(&a_mat, &b_mat).map(|m| m.concat())
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "on_mult_line (2D Vec)".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "on_mult_line (2D Vec)".to_string(),
                duration_secs: None,
            });
        }

        if let Some(duration) = measure_time("on_mult_line_flat", || {
            naive_approach::on_mult_line_flat(&a_flat, &b_flat)
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "on_mult_line_flat".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "on_mult_line_flat".to_string(),
                duration_secs: None,
            });
        }

        if let Some(duration) = measure_time("final_mul_line (Optimized)", || {
            optimized::final_mul_line(&a_flat, &b_flat)
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "final_mul_line (Optimized)".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "final_mul_line (Optimized)".to_string(),
                duration_secs: None,
            });
        }

        if let Some(duration) = measure_time("final_mul_block (Optimized)", || {
            optimized::final_mul_block(&a_flat, &b_flat, 96)
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "final_mul_block (Optimized)".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "final_mul_block (Optimized)".to_string(),
                duration_secs: None,
            });
        }

        if let Some(duration) = measure_time("parallel line", || {
            parallel::final_mul_line_parallel(&a_flat, &b_flat)
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "parallel line".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "parallel line".to_string(),
                duration_secs: None,
            });
        }

        if let Some(duration) = measure_time("parallel block", || {
            parallel::parallel_mul_block(&a_flat, &b_flat, 128)
        }) {
            results.push(ResultRow {
                matrix_size: size,
                method: "parallel block".to_string(),
                duration_secs: Some(duration.as_secs_f64()),
            });
        } else {
            results.push(ResultRow {
                matrix_size: size,
                method: "parallel block".to_string(),
                duration_secs: None,
            });
        }
    }

    // Write results to CSV file "results_rust.csv"
    let file = File::create("results_rust.csv").expect("Unable to create file");
    let mut writer = BufWriter::new(file);
    // Write CSV header
    writeln!(writer, "matrix_size,method,duration_secs").expect("Unable to write header");
    // Write each row
    for row in results {
        let duration_str = row
            .duration_secs
            .map(|d| d.to_string())
            .unwrap_or_else(|| "failed".to_string());
        writeln!(
            writer,
            "{},{},{}",
            row.matrix_size, row.method, duration_str
        )
        .expect("Unable to write row");
    }
    println!("Results saved to results_rust.csv");
}
