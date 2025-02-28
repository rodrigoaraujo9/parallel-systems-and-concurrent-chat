use rayon::prelude::*;

pub fn final_mul_line_parallel(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
    assert_eq!(a.len(), b.len(), "Matrix dimensions do not match");

    let length = a.len();
    let side_f64 = (length as f64).sqrt();

    assert!(side_f64.fract() == 0.0, "Matrix must be a perfect square");

    let side = side_f64 as usize;
    let mut res = vec![0.0; length];

    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }
    //cada thread calcula uma row
    res.par_chunks_exact_mut(side)
        .enumerate()
        .for_each(|(i, res_row)| {
            let a_row = &a[i * side..(i + 1) * side];
            for k in 0..side {
                let a_val = a_row[k]; // a[i][k]
                let b_trans_row = &b_transposed[k * side..(k + 1) * side];
                res_row
                    .iter_mut()
                    .zip(b_trans_row.iter())
                    .for_each(|(r, &b_val)| {
                        *r += a_val * b_val;
                    });
            }
        });

    Some(res)
}

pub fn parallel_mul_block(a: &[f64], b: &[f64], bk_size: usize) -> Option<Vec<f64>> {
    assert_eq!(a.len(), b.len(), "Matrix dimensions do not match");

    let length = a.len();
    let side_f64 = (length as f64).sqrt();

    assert!(side_f64.fract() == 0.0, "Matrix must be a perfect square");

    let side = side_f64 as usize;
    let mut res = vec![0.0; length];

    let mut b_transposed = vec![0.0; length];

    for i in 0..side {
        for j in 0..side {
            b_transposed[j * side + i] = b[i * side + j];
        }
    }

    debug_assert!(bk_size > 0, "Block size must be greater than zero");

    res.par_chunks_mut(side)
        .enumerate()
        .for_each(|(ii, res_chunk)| {
            for jj in (0..side).step_by(bk_size) {
                for kk in (0..side).step_by(bk_size) {
                    let i = ii * side;
                    for k in kk..(kk + bk_size).min(side) {
                        let a_val = a[i + k];
                        let b_row = &b_transposed[k * side..(k + 1) * side];
                        for j in jj..(jj + bk_size).min(side) {
                            res_chunk[j] += a_val * b_row[j];
                        }
                    }
                }
            }
        });

    Some(res)
}
