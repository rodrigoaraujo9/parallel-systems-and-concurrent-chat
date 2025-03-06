use std::env;
use std::time::Instant;

pub fn basic_matrix_multiplication(a: &[f64], b: &[f64], side: usize) -> Option<Vec<f64>> {
    if a.len() != side * side || b.len() != side * side {
        return None;
    }
    let mut result = vec![0.0; side * side];
    for i in 0..side {
        for k in 0..side {
            let a_val = a[i * side + k];
            for j in 0..side {
                result[i * side + j] += a_val * b[k * side + j];
            }
        }
    }
    Some(result)
}

pub fn line_matrix_multiplication(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();
    let side_f64 = (length as f64).sqrt();
    if side_f64.fract() != 0.0 {
        return None;
    }
    let side = side_f64 as usize;
    let mut result = vec![0.0; length];

    for i in 0..side {
        for k in 0..side {
            let a_val = a[i * side + k];
            for j in 0..side {
                result[i * side + j] += a_val * b[k * side + j];
            }
        }
    }
    Some(result)
}

pub fn block_matrix_multiplication(a: &[f64], b: &[f64], block_size: usize) -> Option<Vec<f64>> {
    if a.len() != b.len() {
        return None;
    }
    let length = a.len();
    let side_f64 = (length as f64).sqrt();
    if side_f64.fract() != 0.0 {
        return None;
    }
    let side = side_f64 as usize;
    let mut result = vec![0.0; length];

    for ii in (0..side).step_by(block_size) {
        for jj in (0..side).step_by(block_size) {
            for kk in (0..side).step_by(block_size) {
                for i in ii..(ii + block_size).min(side) {
                    for k in kk..(kk + block_size).min(side) {
                        let a_val = a[i * side + k];
                        let res_row = &mut result[i * side..(i + 1) * side];
                        for j in jj..(jj + block_size).min(side) {
                            res_row[j] += a_val * b[k * side + j];
                        }
                    }
                }
            }
        }
    }
    Some(result)
}

fn generate_matrix(size: usize) -> Vec<f64> {
    (0..size * size).map(|i| (i % 10) as f64).collect()
}

fn main() {
    // Read the number of iterations per matrix size from the command line.
    let args: Vec<String> = env::args().collect();
    let iterations: usize = if args.len() > 1 {
        args[1]
            .parse()
            .expect("Please provide a valid number for iterations")
    } else {
        1
    };

    println!("1: Comparing Basic and Line Multiplication");
    for size in (600..=3000).step_by(400) {
        println!("\nProcessing a matrix of size {}x{}...", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);

        // Basic multiplication: run for 'iterations' times and average the time.
        let mut total_basic_duration = std::time::Duration::new(0, 0);
        let mut basic_result = None;
        for _ in 0..iterations {
            let start = Instant::now();
            let res = basic_matrix_multiplication(&a, &b, size)
                .expect("Basic multiplication failed unexpectedly.");
            total_basic_duration += start.elapsed();
            if basic_result.is_none() {
                basic_result = Some(res);
            }
        }
        let avg_basic_duration = total_basic_duration / (iterations as u32);
        println!(
            "Basic multiplication average over {} iterations: {:.2?}",
            iterations, avg_basic_duration
        );

        // Line multiplication: run for 'iterations' times and average the time.
        let mut total_line_duration = std::time::Duration::new(0, 0);
        let mut line_result = None;
        for _ in 0..iterations {
            let start = Instant::now();
            let res = line_matrix_multiplication(&a, &b)
                .expect("Line multiplication failed unexpectedly.");
            total_line_duration += start.elapsed();
            if line_result.is_none() {
                line_result = Some(res);
            }
        }
        let avg_line_duration = total_line_duration / (iterations as u32);
        println!(
            "Line multiplication average over {} iterations: {:.2?}",
            iterations, avg_line_duration
        );

        // Verify that both methods produce the same result.
        if let (Some(basic), Some(line)) = (basic_result, line_result) {
            if basic.len() == line.len()
                && basic
                    .iter()
                    .zip(line.iter())
                    .all(|(x, y)| (x - y).abs() < 1e-6)
            {
                println!("Success: Both methods produced the same result!");
            } else {
                println!("Warning: The results differ between methods!");
            }
        }
    }

    println!("\n2: Evaluating Block-Oriented Multiplication");
    for size in (4096..=10240).step_by(2048) {
        println!("\nWorking with a {}x{} matrix:", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);
        for &block_size in &[128, 256, 512] {
            let mut total_block_duration = std::time::Duration::new(0, 0);
            for _ in 0..iterations {
                let start = Instant::now();
                let _ = block_matrix_multiplication(&a, &b, block_size)
                    .expect("Block multiplication failed unexpectedly.");
                total_block_duration += start.elapsed();
            }
            let avg_block_duration = total_block_duration / (iterations as u32);
            println!(
                "  Block size {:>3}: average over {} iterations: {:.2?}",
                block_size, iterations, avg_block_duration
            );
        }
    }

    println!("\nAll tests completed.");
}
