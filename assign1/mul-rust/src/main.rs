use csv::Writer;
use std::env;
use std::fs::{create_dir_all, metadata, OpenOptions};
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
                        for j in jj..(jj + block_size).min(side) {
                            result[i * side + j] += a_val * b[k * side + j];
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

/// Writes a record to a CSV file specified by `file_path`. If the file is new (does not exist),
/// the provided `header` is written first.
fn write_csv_record(file_path: &str, record: &[&str], header: &[&str]) {
    let file_exists = metadata(file_path).is_ok();
    let file = OpenOptions::new()
        .append(true)
        .create(true)
        .open(file_path)
        .expect("Unable to open file");
    let mut wtr = Writer::from_writer(file);
    if !file_exists {
        wtr.write_record(header)
            .expect("Unable to write CSV header");
    }
    wtr.write_record(record)
        .expect("Unable to write CSV record");
    wtr.flush().expect("Unable to flush CSV file");
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let iterations = match args.get(1) {
        Some(arg) if arg != "test" => match arg.parse::<usize>() {
            Ok(n) => n,
            Err(_) => {
                eprintln!("Invalid value for iterations: {}. Defaulting to 1.", arg);
                1
            }
        },
        _ => 1,
    };
    let test_flag = args.iter().any(|arg| arg == "test");
    let basic_sizes = if test_flag {
        (600..=1800).step_by(400).collect::<Vec<usize>>()
    } else {
        (600..=3000).step_by(400).collect::<Vec<usize>>()
    };
    let block_sizes = if test_flag {
        (600..=1800).step_by(400).collect::<Vec<usize>>()
    } else {
        (4096..=10240).step_by(2048).collect::<Vec<usize>>()
    };

    // Create folders for each function
    create_dir_all("basic").expect("Unable to create folder 'basic'");
    create_dir_all("line").expect("Unable to create folder 'line'");
    create_dir_all("block").expect("Unable to create folder 'block'");

    println!("1: Comparing Basic and Line Multiplication");
    for &size in &basic_sizes {
        println!("\nProcessing a matrix of size {}x{}...", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);

        // Basic multiplication: results stored in "basic/basic_matrix_<size>.csv"
        let basic_file = format!("basic/basic_matrix_{}.csv", size);
        for iter in 0..iterations {
            let start = Instant::now();
            if let Some(_res) = basic_matrix_multiplication(&a, &b, size) {
                let duration_sec = start.elapsed().as_nanos() as f64 / 1_000_000_000.0;
                write_csv_record(
                    &basic_file,
                    &[&iter.to_string(), &duration_sec.to_string()],
                    &["iteration", "time_sec"],
                );
            } else {
                eprintln!(
                    "Warning: basic_matrix_multiplication returned None for size {}.",
                    size
                );
            }
        }

        // Line multiplication: results stored in "line/line_matrix_<size>.csv"
        let line_file = format!("line/line_matrix_{}.csv", size);
        for iter in 0..iterations {
            let start = Instant::now();
            if let Some(_res) = line_matrix_multiplication(&a, &b) {
                let duration_sec = start.elapsed().as_nanos() as f64 / 1_000_000_000.0;
                write_csv_record(
                    &line_file,
                    &[&iter.to_string(), &duration_sec.to_string()],
                    &["iteration", "time_sec"],
                );
            } else {
                eprintln!(
                    "Warning: line_matrix_multiplication returned None for size {}.",
                    size
                );
            }
        }
    }
    println!("\nExperiment 1 complete. Results are saved in the 'basic' and 'line' folders.");

    println!("\n2: Evaluating Block-Oriented Multiplication");
    for &size in &block_sizes {
        println!("\nWorking with a {}x{} matrix:", size, size);
        let a = generate_matrix(size);
        let b = generate_matrix(size);
        for &block_size in &[128, 256, 512] {
            // Block multiplication: results stored in "block/block_matrix_<size>_block_<block_size>.csv"
            let block_file = format!("block/block_matrix_{}_block_{}.csv", size, block_size);
            for iter in 0..iterations {
                let start = Instant::now();
                if let Some(_res) = block_matrix_multiplication(&a, &b, block_size) {
                    let duration_sec = start.elapsed().as_nanos() as f64 / 1_000_000_000.0;
                    write_csv_record(
                        &block_file,
                        &[&iter.to_string(), &duration_sec.to_string()],
                        &["iteration", "time_sec"],
                    );
                } else {
                    eprintln!(
                        "Warning: block_matrix_multiplication returned None for size {} and block_size {}.",
                        size, block_size
                    );
                }
            }
            println!(
                "  Block size {} done ({} iterations recorded) for matrix size {}.",
                block_size, iterations, size
            );
        }
    }
    println!("\nExperiment 2 complete. Results are saved in the 'block' folder.");
}
