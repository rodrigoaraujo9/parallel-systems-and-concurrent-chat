use rand::Rng;
use std::time::Instant;
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

// WORKS FOR SQUARE MATRICES
fn on_mult_line(a: &Vec<Vec<f64>>, b: &Vec<Vec<f64>>) -> Option<Vec<Vec<f64>>> {
    let rows_a = a.len();
    let rows_b = b.len();
    if rows_a == 0 || rows_b == 0 {
        return None;
    };
    let cols_a = a[0].len();
    let cols_b = b[0].len();
    if cols_a != rows_b {
        return None;
    };
    let mut temp;
    let mut res: Vec<Vec<f64>> = vec![vec![0.0; cols_b]; rows_a];

    for i in 0..rows_a {
        for k in 0..cols_a {
            temp = a[i][k];
            for j in 0..cols_b {
                res[i][j] += temp * b[k][j];
            }
        }
    }
    Some(res)
}

fn on_mult_line_flat(a: &Vec<f64>, b: &Vec<f64>) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();

    let side_f64 = (length as f64).sqrt();

    if side_f64.fract() != 0.0 {
        return None;
    }

    let side = side_f64 as usize;

    let mut res: Vec<f64> = vec![0.0; length];

    let mut temp;

    for i in 0..side {
        for k in 0..side {
            temp = a[i * side + k];
            for j in 0..side {
                res[i * side + j] += temp * b[k * side + j];
            }
        }
    }

    Some(res)
}

fn on_mult_line_flat_transposed_b(a: &Vec<f64>, b: &Vec<f64>) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();

    let side_f64 = (length as f64).sqrt();

    if side_f64.fract() != 0.0 {
        return None;
    }

    let side = side_f64 as usize;

    let mut res: Vec<f64> = vec![0.0; length];

    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }

    for i in 0..side {
        for j in 0..side {
            let mut sum = 0.0;
            for k in 0..side {
                sum += a[i * side + k] * b_transposed[j * side + k];
            }
            res[i * side + j] = sum;
        }
    }

    Some(res)
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
            on_mult_line(&a_mat, &b_mat).map(|m| m.concat())
        });

        measure_time("on_mult_line_flat", || on_mult_line_flat(&a_flat, &b_flat));

        measure_time("on_mult_line_flat_transposed_b", || {
            on_mult_line_flat_transposed_b(&a_flat, &b_flat)
        });

        measure_time("final_mul_line (Optimized)", || {
            optimized::final_mul_line(&a_flat, &b_flat)
        });

        measure_time("optimized (Optimized)", || {
            optimized::optimized(&a_flat, &b_flat)
        });
    }
}
