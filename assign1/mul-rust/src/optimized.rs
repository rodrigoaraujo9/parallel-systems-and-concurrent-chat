pub fn final_mul_line(a: &[f64], b: &[f64]) -> Option<Vec<f64>> {
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

    for (i, a_row) in a.chunks_exact(side).enumerate() {
        let res_row = &mut res[i * side..(i + 1) * side];

        for k in 0..side {
            let a_val = a_row[k];
            let b_trans_row = &b_transposed[k * side..(k + 1) * side];

            res_row
                .iter_mut()
                .zip(b_trans_row.iter())
                .for_each(|(r, &b_val)| {
                    *r += a_val * b_val;
                });
        }
    }

    Some(res)
}

pub fn final_mul_block(a: &[f64], b: &[f64], bk_size: usize) -> Option<Vec<f64>> {
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

    for ii in (0..side).step_by(bk_size) {
        for jj in (0..side).step_by(bk_size) {
            for kk in (0..side).step_by(bk_size) {
                for i in ii..(ii + bk_size).min(side) {
                    let res_row = &mut res[i * side..(i + 1) * side];
                    for k in kk..(kk + bk_size).min(side) {
                        let a_val = a[i * side + k];
                        let b_row = &b_transposed[k * side..(k + 1) * side];
                        for j in jj..(jj + bk_size).min(side) {
                            res_row[j] += a_val * b_row[j];
                        }
                    }
                }
            }
        }
    }

    Some(res)
}
